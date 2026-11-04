package io.github.pdroacordi.llmgateway.gateway.app.config

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.spi.ConnectionFactory
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/** r2dbc-pool has no public reader of its limits, so the configured acquire time is proved by waiting it out. */
@SpringBootTest
class ConnectionPoolWiringTest(
    @Autowired private val connections: ConnectionFactory,
) {
    @Test
    fun `the app's connection factory is a pool that gives up at max-acquire-time`() {
        val pool = assertInstanceOf(ConnectionPool::class.java, connections)

        val took =
            measureTime {
                assertThrows<RuntimeException> { Mono.from(pool.create()).block(Duration.ofSeconds(5)) }
            }

        assertTrue(took in ACQUIRE_TIME) { "took $took, application.yaml says 500ms" }
        assertEquals(MAX_SIZE, pool.metrics.orElseThrow().maxAllocatedSize)
    }

    private companion object {
        const val MAX_SIZE = 10
        val ACQUIRE_TIME = 450.milliseconds..1500.milliseconds
        val HUNG = BlackHoleServer()

        @JvmStatic
        @DynamicPropertySource
        fun hungDatabase(registry: DynamicPropertyRegistry) {
            registry.add("spring.r2dbc.url") { "r2dbc:postgresql://127.0.0.1:${HUNG.port}/unused" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            HUNG.close()
        }
    }
}
