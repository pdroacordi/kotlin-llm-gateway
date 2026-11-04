package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource

@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(
    properties = [
        "logging.level.io.github.pdroacordi.llmgateway=DEBUG",
        // Boot's group for org.springframework.http and .web: a first step when debugging a gateway.
        "logging.level.web=TRACE",
    ],
)
class StreamNoLeakIntegrationTest : MockBackedTest() {
    private val prompted =
        """{"model":"mock-chat","messages":[{"role":"user","content":"$PROMPT"}],"stream":true,""" +
            """"stream_options":{"include_usage":true}}"""

    private fun assertNoCanary(
        text: String,
        output: CapturedOutput,
    ) {
        listOf(PROVIDER_MESSAGE, ORGANIZATION, PROMPT, KEY).forEach { canary ->
            assertFalse(canary in text) { "$canary in the response: $text" }
            assertFalse(canary in output.all) { "$canary in the log" }
        }
    }

    // An absence proves nothing unless the canary was in play.
    private fun assertCanariesWereSent() {
        val entry = journal().single()
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
        assertTrue(PROMPT in entry.at("/body").stringValue())
    }

    @Test
    fun `a success keeps the prompt, the key and the completion out of the log`(output: CapturedOutput) {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"$COMPLETION"}}}""")

        val stream = rawStream(prompted)

        assertTrue(COMPLETION in stream.body) { stream.body }
        eventually { "Stream completed" in output.all }
        assertCanariesWereSent()
        assertNoCanary("", output)
        assertFalse(COMPLETION in output.all) { "the completion in the log" }
    }

    @Test
    fun `an in-band error with canaries leaves nothing in the response or the log`(output: CapturedOutput) {
        scenario(
            """{"otherwise":{"error":{"status":500,"message":"$PROVIDER_MESSAGE $ORGANIZATION"},
               "fault":{"kind":"stream_error","afterPieces":1}}}""",
        )

        val stream = rawStream(prompted)

        eventually { "Stream failed" in output.all }
        assertCanariesWereSent()
        assertNoCanary(stream.body, output)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 429, 500, 503])
    fun `a provider error before the first frame with canaries leaves nothing either`(
        status: Int,
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":{"error":{"status":$status,"message":"$PROVIDER_MESSAGE $ORGANIZATION"}}}""")

        val stream = rawStream(prompted)

        eventually { "with status" in output.all }
        assertCanariesWereSent()
        assertNoCanary(stream.body, output)
    }

    @Test
    fun `a malformed event stays out of the log`(output: CapturedOutput) {
        scenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"$COMPLETION one"},
               "fault":{"kind":"malformed","afterPieces":1}}}""",
        )

        val stream = rawStream(prompted)

        eventually { "Stream failed" in output.all }
        assertCanariesWereSent()
        assertNoCanary(stream.body, output)
        assertFalse(COMPLETION in output.all) { "the completion in the log" }
    }

    private companion object {
        const val PROVIDER_MESSAGE = "canary-provider-message"
        const val ORGANIZATION = "org-canary"
        const val PROMPT = "canary-prompt"

        // One word: the mock sends it as one piece, so a log of a piece would show it whole.
        const val COMPLETION = "canarycompletion"
    }
}
