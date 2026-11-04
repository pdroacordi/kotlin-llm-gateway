package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only a Linux run can trip the /dev/urandom read of a new connection (GW-04 spike S4). */
@ExtendWith(OutputCaptureExtension::class)
class TeamStoreBlockHoundIntegrationTest : PostgresBackedTest() {
    @Test
    fun `concurrent lookups of cold keys pass BlockHound on the event loop`(output: CapturedOutput) {
        val team = createTeam()
        val keys = List(KEYS) { createKey(team).get("key").stringValue() }
        val pool = Executors.newFixedThreadPool(CONCURRENCY)

        val statuses =
            try {
                pool
                    .invokeAll(
                        keys.map { key ->
                            Callable {
                                chatAs(key)
                                    .expectBody()
                                    .returnResult()
                                    .status
                                    .value()
                            }
                        },
                        60,
                        TimeUnit.SECONDS,
                    ).map { it.get() }
            } finally {
                pool.shutdownNow()
            }

        assertEquals(setOf(200), statuses.toSet())
        assertFalse("BlockingOperationError" in output.all) { output.all }
    }

    private companion object {
        const val KEYS = 64
        const val CONCURRENCY = 16

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            register(registry, SharedPostgres.database("team_store_block_hound"))
        }
    }
}
