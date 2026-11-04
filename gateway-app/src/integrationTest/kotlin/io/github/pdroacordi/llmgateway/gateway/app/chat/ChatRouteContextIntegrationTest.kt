package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource

/** A `coRouter` handler runs inside the filter's coroutine context, MDC included, after the upstream call too. */
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["logging.level.io.github.pdroacordi.llmgateway=DEBUG"])
class ChatRouteContextIntegrationTest : MockBackedTest() {
    private fun requestIdOf(status: Int): String =
        checkNotNull(
            chat()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(String::class.java)
                .returnResult()
                .responseHeaders
                .getFirst("x-request-id"),
        )

    @Test
    fun `the handler's RequestContext is the one in x-request-id`() {
        val requestId = requestIdOf(200)

        assertEquals(1, journalOf(requestId).size)
    }

    @Test
    fun `a log line written after the upstream call carries the request ID`(output: CapturedOutput) {
        val requestId = requestIdOf(200)

        assertTrue(output.all.lines().any { "[$requestId] " in it && "Provider call answered" in it }) { output.all }
    }

    @Test
    fun `so does the line for a failed call`(output: CapturedOutput) {
        scenario("""{"otherwise":{"error":{"status":429}}}""")

        val requestId = requestIdOf(429)

        assertTrue(output.all.lines().any { "[$requestId] " in it && "Provider call failed" in it }) { output.all }
    }
}
