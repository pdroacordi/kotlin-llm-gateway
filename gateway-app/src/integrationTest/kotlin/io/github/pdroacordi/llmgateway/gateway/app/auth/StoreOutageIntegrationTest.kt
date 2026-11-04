package io.github.pdroacordi.llmgateway.gateway.app.auth

import com.github.dockerjava.api.DockerClient
import io.github.pdroacordi.llmgateway.testsupport.PostgresDatabase
import io.github.pdroacordi.llmgateway.testsupport.StackImages
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTimedValue

/** A private container, because a pause would hit every other suite on a shared one. */
class StoreOutageIntegrationTest : PostgresBackedTest() {
    private fun status(key: String): Int =
        chatAs(key)
            .expectBody()
            .returnResult()
            .status
            .value()

    @Test
    fun `with Postgres hung a cached key gets 200, an uncached one 503 in time, health stays UP, and it recovers`() {
        val team = createTeam()
        val cached = createKey(team).get("key").stringValue()
        val uncached = createKey(team).get("key").stringValue()
        assertEquals(200, status(cached))

        docker.pauseContainerCmd(POSTGRES.containerId).exec()
        try {
            assertEquals(200, status(cached))
            val (answer, took) = measureTimedValue { status(uncached) }
            assertEquals(503, answer)
            assertTrue(took <= 1500.milliseconds) { "took $took" }
            assertEquals(
                503,
                admin
                    .get()
                    .uri("/admin/v1/teams")
                    .exchange()
                    .expectBody()
                    .returnResult()
                    .status
                    .value(),
            )
            gateway
                .get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus()
                .isOk
        } finally {
            docker.unpauseContainerCmd(POSTGRES.containerId).exec()
        }

        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .until { status(uncached) == 200 }
    }

    private companion object {
        val docker: DockerClient = DockerClientFactory.instance().client()
        val POSTGRES: PostgreSQLContainer =
            PostgreSQLContainer(StackImages.POSTGRES).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val database =
                PostgresDatabase(
                    POSTGRES.host,
                    POSTGRES.firstMappedPort,
                    POSTGRES.databaseName,
                    POSTGRES.username,
                    POSTGRES.password,
                )
            register(registry, database)
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            POSTGRES.stop()
        }
    }
}
