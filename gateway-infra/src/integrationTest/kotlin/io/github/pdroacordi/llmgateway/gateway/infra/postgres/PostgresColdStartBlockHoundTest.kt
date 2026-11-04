package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import kotlinx.coroutines.reactor.mono
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

// On a non-blocking thread, as a request's lookup is. Only a Linux run can trip the /dev/urandom read (GW-04 spike S4).
class PostgresColdStartBlockHoundTest {
    private val database = SharedPostgres.database("cold_start").also { TestPostgres.migrate(it) }
    private val digest = TestKeys.hasher().digest("nobody")
    private val coldAcquire = Duration.ofSeconds(10)

    @Test
    fun `the first lookup on a fresh pool passes BlockHound`() {
        val pool = TestPostgres.pool(database, maxAcquireTime = coldAcquire, warm = false)
        val store = PostgresTeamStore(pool, 10.seconds)

        val result =
            mono { store.findKey(digest) }
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(20))

        assertEquals(StoreResult.Ok(null), result)
    }

    @Test
    fun `many new connections at once pass BlockHound`() {
        val pool = TestPostgres.pool(database, maxSize = 8, maxAcquireTime = coldAcquire, warm = false)
        val store = PostgresTeamStore(pool, 10.seconds)

        val results =
            Flux
                .range(0, 40)
                .flatMap({ mono { store.findKey(digest) }.subscribeOn(Schedulers.parallel()) }, 8)
                .collectList()
                .block(Duration.ofSeconds(30))
                .orEmpty()

        assertEquals(setOf<StoreResult<*>>(StoreResult.Ok(null)), results.toSet())
    }
}
