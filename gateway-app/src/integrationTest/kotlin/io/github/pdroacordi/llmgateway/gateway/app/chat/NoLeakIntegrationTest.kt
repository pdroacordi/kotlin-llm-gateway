package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
class NoLeakIntegrationTest : MockBackedTest() {
    private val prompted = """{"model":"mock-chat","messages":[{"role":"user","content":"$PROMPT"}]}"""

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
    private fun assertCanaryKeyWasSent() {
        assertEquals(fingerprint("sk-canary-key"), journal().single().at("/credential/fingerprint").stringValue())
    }

    // The mapper logs after the write, so the client can have the response before the line exists.
    private fun awaitMapperLine(output: CapturedOutput) {
        eventually { "with status" in output.all }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 429, 500, 503])
    fun `a provider error with canaries leaves nothing in the response or the log`(
        status: Int,
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":{"error":{"status":$status,"message":"$PROVIDER_MESSAGE $ORGANIZATION"}}}""")

        val body =
            chat(prompted)
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
                ?.decodeToString()
                .orEmpty()

        awaitMapperLine(output)
        assertCanaryKeyWasSent()
        assertNoCanary(body, output)
    }

    @Test
    fun `a success keeps the prompt, the key and the completion out of the log`(output: CapturedOutput) {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"$COMPLETION"}}}""")

        val body =
            chat(prompted)
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
                ?.decodeToString()
                .orEmpty()

        assertEquals(COMPLETION, mapper.readTree(body).at("/choices/0/message/content").stringValue())
        eventually { "Provider call answered" in output.all }
        assertCanaryKeyWasSent()
        assertNoCanary(body, output)
        assertFalse(COMPLETION in output.all) { "the completion in the log" }
    }

    @Test
    fun `an unreadable provider body stays out of the log`(output: CapturedOutput) {
        scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""")

        val body =
            chat(prompted)
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
                ?.decodeToString()
                .orEmpty()

        awaitMapperLine(output)
        assertCanaryKeyWasSent()
        assertNoCanary(body, output)
        assertFalse("chatcmpl-mock" in output.all) { output.all }
    }

    private companion object {
        const val PROVIDER_MESSAGE = "canary-provider-message"
        const val ORGANIZATION = "org-canary"
        const val PROMPT = "canary-prompt"
        const val COMPLETION = "canary-completion"
    }
}
