package io.github.pdroacordi.llmgateway.gateway.app.chat

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
class NoLeakIntegrationTest : MockBackedTest() {
    private val prompted = """{"model":"mock-chat","messages":[{"role":"user","content":"$PROMPT"}]}"""
    private val structured =
        """{"model":"mock-chat","messages":[{"role":"user","content":"$PROMPT"}],
        "tools":[{"type":"function","function":{"name":"g","parameters":{"description":"$SCHEMA"}}}],
        "response_format":{"type":"json_schema","json_schema":{"name":"r","schema":{"description":"$SCHEMA"}}}}"""

    private fun assertNoCanary(
        text: String,
        output: CapturedOutput,
    ) {
        listOf(PROVIDER_MESSAGE, ORGANIZATION, PROMPT, KEY, TestKeys.TEAM_KEY).forEach { canary ->
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
    fun `a schema, a tool definition and tool arguments stay out of the log, on both forms`(output: CapturedOutput) {
        scenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"",
            "toolCalls":[{"name":"g","input":{"x":"$TOOL_ARGUMENT"}}]}}}""",
        )

        val body =
            chat(structured)
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
                ?.decodeToString()
                .orEmpty()
        val stream = rawStream(structured.dropLast(1) + ""","stream":true}""")

        eventually { "Stream completed" in output.all }
        assertEquals(200, stream.status) { stream.body }
        assertTrue(TOOL_ARGUMENT in body) { body }
        journal().forEach { assertTrue(SCHEMA in it.at("/body").stringValue()) { "the schema was not in play" } }
        assertEquals(2, journal().size)
        assertFalse(SCHEMA in output.all) { "the schema in the log" }
        assertFalse(TOOL_ARGUMENT in output.all) { "the arguments in the log" }
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
        const val SCHEMA = "canary-schema"
        const val TOOL_ARGUMENT = "canary-tool-argument"
    }
}

@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(
    properties = [
        "logging.level.io.github.pdroacordi.llmgateway=DEBUG",
        "logging.level.web=TRACE",
    ],
)
class AnthropicNoLeakIntegrationTest : AnthropicBackedTest() {
    private val prompted = """{"model":"mock-claude","messages":[{"role":"user","content":"$PROMPT"}]}"""
    private val tooled =
        """{"model":"mock-claude","messages":[{"role":"user","content":"$PROMPT"},
        {"role":"assistant","tool_calls":[{"id":"t","type":"function",
        "function":{"name":"g","arguments":"{\"x\":\"$ARGUMENT\"}"}}]},
        {"role":"tool","tool_call_id":"t","content":"$ARGUMENT"}]}"""

    private fun assertNoCanary(
        text: String,
        output: CapturedOutput,
    ) {
        listOf(PROVIDER_MESSAGE, PROMPT, ARGUMENT, ANTHROPIC_KEY, TestKeys.TEAM_KEY).forEach { canary ->
            assertFalse(canary in text) { "$canary in the response: $text" }
            assertFalse(canary in output.all) { "$canary in the log" }
        }
    }

    // An absence proves nothing unless the canaries were in play.
    private fun assertCanariesWereSent() {
        val entry = journal().single()
        assertEquals(fingerprint(ANTHROPIC_KEY), entry.at("/credential/fingerprint").stringValue())
        assertTrue(ARGUMENT in entry.at("/body").stringValue())
    }

    private fun body(request: String): String =
        chat(request)
            .expectBody(ByteArray::class.java)
            .returnResult()
            .responseBody
            ?.decodeToString()
            .orEmpty()

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 402, 403, 404, 429, 500, 503, 529])
    fun `a provider error with canaries leaves nothing in the response or the log`(
        status: Int,
        output: CapturedOutput,
    ) {
        claudeScenario("""{"otherwise":{"error":{"status":$status,"message":"$PROVIDER_MESSAGE"}}}""")

        val body = body(tooled)

        eventually { "with status" in output.all }
        assertCanariesWereSent()
        assertNoCanary(body, output)
    }

    @Test
    fun `an in-band error frame leaves nothing in the response or the log`(output: CapturedOutput) {
        claudeScenario(
            """{"otherwise":{"error":{"status":529,"message":"$PROVIDER_MESSAGE"},
            "fault":{"kind":"stream_error","afterPieces":1}}}""",
        )

        val stream = rawStream(tooled.dropLast(1) + ""","stream":true}""")

        eventually { "Provider stream failed" in output.all }
        assertCanariesWereSent()
        assertNoCanary(stream.body, output)
    }

    @Test
    fun `a success keeps the prompt, the key, the thinking and the completion out of the log`(output: CapturedOutput) {
        claudeScenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"$COMPLETION",
            "thinking":[{"signature":"$SIGNATURE"}]}}}""",
        )

        val body = body(prompted)

        eventually { "Provider call answered" in output.all }
        assertEquals(COMPLETION, mapper.readTree(body).at("/choices/0/message/content").stringValue())
        assertFalse(COMPLETION in output.all) { "the completion in the log" }
        assertFalse(SIGNATURE in output.all) { "the signature in the log" }
        assertFalse(PROMPT in output.all) { "the prompt in the log" }
    }

    @Test
    fun `a schema stays out of the log, on both forms`(output: CapturedOutput) {
        val request =
            """{"model":"mock-claude","messages":[{"role":"user","content":"$PROMPT"}],
            "response_format":{"type":"json_schema","json_schema":{"schema":{"description":"$SCHEMA"}}}}"""

        val body = body(request)
        val stream = rawStream(request.dropLast(1) + ""","stream":true}""")

        eventually { "Stream completed" in output.all }
        assertEquals(200, stream.status) { stream.body }
        assertFalse(SCHEMA in body) { body }
        journal().forEach { assertTrue(SCHEMA in it.at("/body").stringValue()) { "the schema was not in play" } }
        assertEquals(2, journal().size)
        assertFalse(SCHEMA in output.all) { "the schema in the log" }
    }

    @Test
    fun `a refused schema stays out of the response and the log`(output: CapturedOutput) {
        val request =
            """{"model":"mock-claude","messages":[{"role":"user","content":"$PROMPT"}],
            "response_format":{"type":"json_object","json_schema":{"schema":{"description":"$SCHEMA"}}}}"""

        val body = body(request)

        eventually { "Provider call refused" in output.all }
        assertFalse(SCHEMA in body) { body }
        assertFalse(SCHEMA in output.all) { "the schema in the log" }
    }

    @Test
    fun `a refused request keeps its value out of the log, and a hashed user out of both`(output: CapturedOutput) {
        val request =
            """{"model":"mock-claude","user":"$ARGUMENT","stop":7,"messages":[{"role":"user","content":"$PROMPT"}]}"""

        val body = body(request)

        eventually { "Provider call refused" in output.all }
        assertFalse(ARGUMENT in body) { body }
        assertFalse(ARGUMENT in output.all) { output.all }
    }

    private companion object {
        const val PROVIDER_MESSAGE = "canary-provider-message"
        const val PROMPT = "canary-prompt"
        const val ARGUMENT = "canary-tool-argument"
        const val COMPLETION = "canary-completion"
        const val SIGNATURE = "canary-signature"
        const val SCHEMA = "canary-schema"
    }
}
