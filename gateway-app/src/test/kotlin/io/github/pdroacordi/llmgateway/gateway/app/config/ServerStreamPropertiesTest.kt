package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.isolatedGateway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class ServerStreamPropertiesTest {
    private val idle = Duration.ofSeconds(60)

    private fun message(timeout: Duration): String =
        assertThrows<IllegalStateException> { ServerStreamProperties(timeout).validate(idle) }.message.orEmpty()

    @Test
    fun `a timeout below idle-timeout passes`() {
        assertDoesNotThrow { ServerStreamProperties(Duration.ofSeconds(30)).validate(idle) }
    }

    @ParameterizedTest
    @ValueSource(longs = [0, -1])
    fun `a timeout that is not positive is refused`(seconds: Long) {
        assertEquals("llmgw.server.client-write-timeout must be positive.", message(Duration.ofSeconds(seconds)))
    }

    @ParameterizedTest
    @ValueSource(longs = [60, 61])
    fun `a timeout at or above idle-timeout is refused, naming both properties`(seconds: Long) {
        val message = message(Duration.ofSeconds(seconds))

        assertTrue("llmgw.server.client-write-timeout" in message) { message }
        assertTrue("llmgw.providers.openai.stream.idle-timeout" in message) { message }
        assertTrue("llmgw.providers.anthropic.stream.idle-timeout" in message) { message }
    }

    @Test
    fun `startup refuses a write timeout at idle-timeout`() {
        val failure =
            assertThrows<Throwable> {
                isolatedGateway()
                    .run(
                        "--server.port=0",
                        "--llmgw.server.client-write-timeout=60s",
                        "--llmgw.providers.openai.stream.idle-timeout=60s",
                    ).close()
            }

        val chain = generateSequence(failure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
        assertTrue("client-write-timeout" in chain) { chain }
    }
}
