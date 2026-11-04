package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.GatewayApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

/** An adapter's pool is all `close()` disposes, and a disposed pool serves on, so its log line is what can be seen. */
@ExtendWith(OutputCaptureExtension::class)
class ProviderLifecycleTest {
    @Test
    fun `context shutdown closes each enabled adapter, once`(output: CapturedOutput) {
        val mark = output.length
        val context =
            SpringApplicationBuilder(GatewayApplication::class.java).run(
                "--server.port=0",
                "--llmgw.providers.anthropic.enabled=true",
                "--llmgw.providers.anthropic.api-key=k",
            )
        assertTrue("Provider pool closed" !in output.all.substring(mark))

        context.close()

        val lines =
            output.all
                .substring(mark)
                .lines()
                .filter { "Provider pool closed" in it }
        assertEquals(1, lines.count { "name=openai" in it }) { lines.toString() }
        assertEquals(1, lines.count { "name=anthropic" in it }) { lines.toString() }
    }
}
