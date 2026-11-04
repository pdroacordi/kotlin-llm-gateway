package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.PostgresDatabase
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import io.github.pdroacordi.llmgateway.testsupport.StackImages
import io.r2dbc.pool.ConnectionPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DataAccessException
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * A private container, because a pause or a stop would hit every other suite on a shared one. A lookup that must run
 * past a blocked test thread uses `Unconfined`, which is what `CoWebFilter` starts with.
 */
@Suppress("InjectDispatcher")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PostgresTeamStoreFailureIntegrationTest {
    private val container = PostgreSQLContainer(StackImages.POSTGRES)
    private lateinit var database: PostgresDatabase
    private val digest = TestKeys.hasher().digest("nobody")
    private val unavailable = StoreResult.Failed(TeamStoreFailure.Unavailable)

    @BeforeAll
    fun start() {
        container.start()
        database =
            PostgresDatabase(
                container.host,
                container.firstMappedPort,
                container.databaseName,
                container.username,
                container.password,
            )
        TestPostgres.migrate(database)
    }

    @AfterAll
    fun stop() {
        container.stop()
    }

    private fun pause() {
        DockerClientFactory
            .instance()
            .client()
            .pauseContainerCmd(container.containerId)
            .exec()
    }

    private fun unpause() {
        DockerClientFactory
            .instance()
            .client()
            .unpauseContainerCmd(container.containerId)
            .exec()
    }

    private fun acquired(pool: ConnectionPool): Int =
        pool.metrics
            .orElseThrow()
            .acquiredSize()

    @Test
    @Order(1)
    fun `an exhausted pool gives Unavailable within max-acquire-time`() =
        runBlocking {
            val pool = TestPostgres.pool(database, maxSize = 1, maxAcquireTime = Duration.ofMillis(300))
            val held = checkNotNull(pool.create().block(Duration.ofSeconds(5)))
            val store = PostgresTeamStore(pool, 5.seconds)
            try {
                var result: StoreResult<*>? = null
                val took = measureTime { result = store.findKey(digest) }

                assertEquals(unavailable, result)
                assertTrue(took < 2.seconds) { "took $took" }
            } finally {
                Mono.from(held.close()).block(Duration.ofSeconds(5))
                pool.dispose()
            }
        }

    @Test
    @Order(2)
    fun `a bad statement is an exception, not Unavailable`() =
        runBlocking {
            // A database with no tables: the statement is what is wrong, and the connection is fine.
            val pool = TestPostgres.pool(SharedPostgres.database("no_tables"))
            val store = PostgresTeamStore(pool, 5.seconds)

            assertThrows<DataAccessException> { store.findKey(digest) }
            pool.dispose()
        }

    @Test
    @Order(3)
    fun `a cancelled call frees its connection`() =
        runBlocking {
            val pool = TestPostgres.pool(database)
            val store = PostgresTeamStore(pool, 30.seconds)
            // The lookup waits on this lock, so it is cancelled mid-query.
            TestPostgres.jdbc(database) { locker ->
                locker.autoCommit = false
                locker.createStatement().use { it.execute("LOCK TABLE team_keys IN ACCESS EXCLUSIVE MODE") }

                runBlocking {
                    val lookup = async(Dispatchers.Unconfined) { store.findKey(digest) }
                    await().atMost(Duration.ofSeconds(5)).until { acquired(pool) == 1 }
                    delay(100.milliseconds)
                    lookup.cancelAndJoin()
                }

                await().atMost(Duration.ofSeconds(5)).until { acquired(pool) == 0 }
                locker.rollback()
            }
            assertEquals(StoreResult.Ok(null), store.findKey(digest))
            pool.dispose()
        }

    @Test
    @Order(4)
    fun `a query held past the deadline gives Unavailable on a thread BlockHound watches`() {
        val pool = TestPostgres.pool(database)
        val store = PostgresTeamStore(pool, 300.milliseconds)
        TestPostgres.jdbc(database) { locker ->
            locker.autoCommit = false
            locker.createStatement().use { it.execute("LOCK TABLE team_keys IN ACCESS EXCLUSIVE MODE") }

            val thread =
                mono(Dispatchers.Unconfined) {
                    assertEquals(unavailable, store.findKey(digest))
                    Thread.currentThread().name
                }.block(Duration.ofSeconds(5))

            assertTrue(thread.orEmpty().startsWith("parallel-")) { "resumed on $thread" }
            await().atMost(Duration.ofSeconds(5)).until { acquired(pool) == 0 }
            locker.rollback()
        }
        pool.dispose()
    }

    @Test
    @Order(5)
    fun `a paused server gives Unavailable within the deadline, and a recovery after it`() =
        runBlocking {
            val pool = TestPostgres.pool(database)
            val store = PostgresTeamStore(pool, 1.seconds)
            assertEquals(StoreResult.Ok(null), store.findKey(digest))
            pause()
            try {
                var result: StoreResult<*>? = null
                val took = measureTime { result = store.findKey(digest) }

                assertEquals(unavailable, result)
                assertTrue(took < 1500.milliseconds) { "took $took" }
            } finally {
                unpause()
            }
            await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until { runBlocking { store.findKey(digest) } == StoreResult.Ok(null) }
            pool.dispose()
        }

    @Test
    @Order(6)
    fun `a stopped server gives Unavailable`() =
        runBlocking {
            val pool = TestPostgres.pool(database)
            val store = PostgresTeamStore(pool, 1.seconds)
            assertEquals(StoreResult.Ok(null), store.findKey(digest))
            container.stop()

            assertEquals(unavailable, store.findKey(digest))
            pool.dispose()
        }
}
