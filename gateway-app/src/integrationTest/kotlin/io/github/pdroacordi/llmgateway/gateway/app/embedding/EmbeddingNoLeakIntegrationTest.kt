package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
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
class EmbeddingNoLeakIntegrationTest : EmbeddingBackedTest() {
    private val request =
        """{"model":"$EMBED_MODEL","input":["$INPUT"],"user":"$USER","dimensions":$DIMENSIONS}"""

    private fun assertAbsent(
        canaries: List<String>,
        text: String,
        output: CapturedOutput,
    ) {
        canaries.forEach { canary ->
            assertFalse(canary in text) { "$canary in the response: $text" }
            assertFalse(canary in output.all) { "$canary in the log" }
        }
    }

    // An absence proves nothing unless the canaries were in play.
    private fun assertCanariesWereSent() {
        val entry = journal().single()
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
        assertTrue(INPUT in entry.at("/body").stringValue())
    }

    @Test
    fun `a float success keeps the input, the user, the key and the vector out of the log`(output: CapturedOutput) {
        val vector =
            direct(request)
                .body
                .at("/data/0/embedding")
                .values()
                .map { it.asString() }
                .filter { it.length >= MEANINGFUL_LITERAL }
        assertTrue(vector.isNotEmpty())
        resetMock()

        val reply = embed(request)

        assertEquals(200, reply.status)
        eventually { "Provider call answered" in output.all }
        assertCanariesWereSent()
        assertAbsent(listOf(INPUT, USER, KEY, TestKeys.TEAM_KEY), reply.bytes.decodeToString(), output)
        vector.forEach { assertFalse(it in output.all) { "$it, a vector literal, in the log" } }
    }

    @Test
    fun `a base64 success keeps the vector out of the log`(output: CapturedOutput) {
        val base64 = request.dropLast(1) + ""","encoding_format":"base64"}"""
        val vector = direct(base64).body.at("/data/0/embedding").stringValue()
        resetMock()

        val reply = embed(base64)

        assertEquals(vector, reply.body.at("/data/0/embedding").stringValue())
        eventually { "Provider call answered" in output.all }
        assertFalse(vector in output.all) { "the vector in the log" }
    }

    @Test
    fun `token IDs stay out of the log`(output: CapturedOutput) {
        val reply = embed("""{"model":"$EMBED_MODEL","input":[$TOKEN_ID]}""")

        assertEquals(200, reply.status)
        eventually { "Provider call answered" in output.all }
        assertFalse(TOKEN_ID.toString() in output.all) { "a token ID in the log" }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 404, 429, 500, 503])
    fun `a provider error with canaries leaves nothing in the response or the log`(
        status: Int,
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":{"error":{"status":$status,"message":"$PROVIDER_MESSAGE"}}}""", EMBED_MODEL)

        val reply = embed(request)

        eventually { "with status" in output.all }
        assertCanariesWereSent()
        assertAbsent(listOf(PROVIDER_MESSAGE, INPUT, USER, KEY), reply.bytes.decodeToString(), output)
    }

    @Test
    fun `an unreadable provider body stays out of the log`(output: CapturedOutput) {
        scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""", EMBED_MODEL)

        val reply = embed(request)

        assertEquals(502, reply.status)
        eventually { "with status" in output.all }
        assertCanariesWereSent()
        // The start of the mock's malformed body, which is what must stay out.
        assertAbsent(listOf(INPUT, USER, KEY, "chatcmpl-mock"), reply.bytes.decodeToString(), output)
    }

    @Test
    fun `a request the gateway refuses keeps its input out of the response and the log`(output: CapturedOutput) {
        val reply = embed("""{"model":"$EMBED_MODEL","input":["$INPUT",1]}""")

        assertEquals(400, reply.status)
        eventually { "with status" in output.all }
        assertAbsent(listOf(INPUT), reply.bytes.decodeToString(), output)
    }

    private companion object {
        const val INPUT = "canary input words"
        const val USER = "canary-user"
        const val PROVIDER_MESSAGE = "canary-provider-message"
        const val DIMENSIONS = 7
        const val TOKEN_ID = 987_654_321

        // A short literal such as 0.0 can appear in a log by chance.
        const val MEANINGFUL_LITERAL = 6
    }
}
