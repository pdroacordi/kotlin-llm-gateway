package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.GatewayApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class StartupFailureTest {
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    private fun start(vararg args: String): Throwable =
        assertThrows<Throwable> {
            SpringApplicationBuilder(GatewayApplication::class.java).run("--server.port=0", *args).close()
        }

    private fun Throwable.chain(): String =
        generateSequence(this) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `a blank key stops the app with a message naming the variable`(output: CapturedOutput) {
        val failure = start("--llmgw.providers.openai.api-key=")

        assertTrue("OPENAI_API_KEY" in failure.chain()) { failure.chain() }
        assertFalse("test-key-not-real" in output.all)
    }

    @Test
    fun `a key with a trailing newline stops the app with a message naming the variable`(output: CapturedOutput) {
        val failure = start("--llmgw.providers.openai.api-key=$CANARY\n")

        assertTrue("OPENAI_API_KEY" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `an invalid timeout stops the app and the output does not hold the key`(output: CapturedOutput) {
        val failure =
            start(
                "--llmgw.providers.openai.api-key=$CANARY",
                "--llmgw.providers.openai.request-timeout=not-a-duration",
            )

        assertTrue("request-timeout" in failure.chain() + output.all) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `a value toAdapterConfig rejects stops the app and the output does not hold the key`(output: CapturedOutput) {
        val failure =
            start(
                "--llmgw.providers.openai.api-key=$CANARY",
                "--llmgw.providers.openai.request-timeout=0s",
            )

        assertTrue("request-timeout" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `a non-loopback bind without the flag stops the app, naming the property`(output: CapturedOutput) {
        val failure = start("--server.address=0.0.0.0")

        assertTrue("llmgw.security.allow-unauthenticated-exposure" in failure.chain()) { failure.chain() }
        assertFalse("Netty started" in output.all.substring(mark)) { output.all }
    }

    private companion object {
        const val CANARY = "sk-canary-key"
    }
}
