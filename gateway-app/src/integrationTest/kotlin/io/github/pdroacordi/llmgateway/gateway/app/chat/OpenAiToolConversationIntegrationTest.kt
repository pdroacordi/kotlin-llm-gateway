package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode

/**
 * The proof of ADR-GW-06-006: `mock-chat` goes to the OpenAI adapter, and [AnthropicBackedTest] gives the journal and
 * stream helpers. The mock does not constrain output, so nothing here proves conformance (ADR-GW-06-005).
 */
class OpenAiToolConversationIntegrationTest : AnthropicBackedTest() {
    private fun tree(json: String): JsonNode = mapper.readTree(json)

    private fun firstTurn(extra: String = ""): String =
        """{"model":"mock-chat","tools":[$WEATHER],"tool_choice":$CHOICE,"parallel_tool_calls":false$extra,
        "messages":[$USER]}"""

    private fun secondTurn(
        assistant: String,
        results: String,
        extra: String = "",
    ): String = """{"model":"mock-chat","tools":[$WEATHER]$extra,"messages":[$USER,$assistant,$results]}"""

    private fun formatted(
        format: String,
        extra: String = "",
    ): String = """{"model":"mock-chat","response_format":$format$extra,"messages":[$USER]}"""

    private fun assertToolMembersSent(requestId: String) {
        val sent = upstreamBody(requestId)

        assertEquals(tree("[$WEATHER]"), sent.get("tools"))
        assertEquals(tree(CHOICE), sent.get("tool_choice"))
        assertEquals(tree("false"), sent.get("parallel_tool_calls"))
    }

    private fun assertEchoSent(
        requestId: String,
        assistant: String,
        vararg results: String,
    ) {
        val messages = upstreamBody(requestId).get("messages")

        assertEquals(tree("[$USER,$assistant,${results.joinToString(",")}]"), messages)
    }

    @Test
    fun `a two-turn tool conversation passes through on complete, echoing the reply whole`() {
        scenario(ONE_TOOL)

        val first = answer(firstTurn())
        val message = first.body.at("/choices/0/message")
        val call = message.at("/tool_calls/0")

        assertEquals("tool_calls", first.body.at("/choices/0/finish_reason").stringValue())
        assertEquals("call_1", call.get("id").stringValue())
        assertEquals("function", call.get("type").stringValue())
        assertEquals("get_weather", call.at("/function/name").stringValue())
        assertEquals(PARIS_ARGUMENTS, call.at("/function/arguments").stringValue())
        assertTrue(message.get("content").isNull) { message.toString() }
        assertToolMembersSent(first.requestId)

        val assistant = mapper.writeValueAsString(message)
        val second = answer(secondTurn(assistant, ANSWER_1))

        assertEquals("Sunny.", second.body.at("/choices/0/message/content").stringValue())
        assertEchoSent(second.requestId, assistant, ANSWER_1)
    }

    @Test
    fun `a two-turn tool conversation passes through on stream`() {
        scenario(ONE_TOOL)

        val stream = rawStream(firstTurn(STREAMING))
        val call = stream.toolCalls().getValue(0)

        assertEquals("openai", stream.header("x-gateway-deployment"))
        assertEquals("call_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals(PARIS_ARGUMENTS, call.arguments.toString())
        assertEquals(
            "tool_calls",
            stream
                .chunks()
                .last()
                .at("/choices/0/finish_reason")
                .stringValue(),
        )
        assertToolMembersSent(stream.requestId)

        val arguments = mapper.writeValueAsString(call.arguments.toString())
        val assistant =
            """{"role":"assistant","content":null,"tool_calls":[{"id":"${call.id}","type":"function",
            "function":{"name":"${call.name}","arguments":$arguments}}]}"""
        val second = rawStream(secondTurn(assistant, ANSWER_1, STREAMING))

        assertEquals(200, second.status) { second.body }
        assertEquals("data: [DONE]", second.frames.last())
        assertEchoSent(second.requestId, assistant, ANSWER_1)
    }

    @Test
    fun `two parallel calls - both tool messages reach the journal, and the mock's check accepts them`() {
        scenario(TWO_TOOLS)

        val first = answer(firstTurn())
        val assistant = mapper.writeValueAsString(first.body.at("/choices/0/message"))
        val second = answer(secondTurn(assistant, "$ANSWER_1,$ANSWER_2"))

        assertEquals(
            listOf("call_1", "call_2"),
            first.body
                .at("/choices/0/message/tool_calls")
                .values()
                .map { it.get("id").stringValue() },
        )
        assertEchoSent(second.requestId, assistant, ANSWER_1, ANSWER_2)
    }

    @Test
    fun `on a stream arguments arrive in more than one chunk per call, and joined by index equal the script`() {
        scenario(TWO_TOOLS)

        val stream = rawStream(firstTurn(STREAMING))
        val pieces =
            stream
                .chunks()
                .flatMap { it.at("/choices/0/delta/tool_calls").values() }
                .filter { it.at("/function/arguments").stringValue("").isNotEmpty() }
                .groupBy { it.get("index").intValue() }

        assertEquals(setOf(0, 1), pieces.keys)
        pieces.values.forEach { assertTrue(it.size > 1) { it.toString() } }
        assertEquals(
            PARIS_ARGUMENTS,
            stream
                .toolCalls()
                .getValue(0)
                .arguments
                .toString(),
        )
        assertEquals(
            """{"zone":"CET"}""",
            stream
                .toolCalls()
                .getValue(1)
                .arguments
                .toString(),
        )
    }

    @Test
    fun `a tool message answering no call fails the mock's check, so the check is live`() {
        val assistant = """{"role":"assistant","content":null,"tool_calls":[{"id":"call_9"}]}"""

        val result = chat(secondTurn(assistant, ANSWER_1)).result()
        val error = tree(checkNotNull(result.responseBody)).get("error")

        assertEquals(400, result.status.value()) { result.responseBody }
        assertEquals("messages[2]", error.get("param").stringValue())
    }

    @ParameterizedTest
    @ValueSource(strings = [TEXT, JSON_OBJECT, JSON_SCHEMA])
    fun `each response_format form reaches the journal with the same value, on complete and on stream`(format: String) {
        val requests = listOf(answer(formatted(format)).requestId, rawStream(formatted(format, STREAMING)).requestId)

        requests.forEach { requestId ->
            val raw = journalOf(requestId).single().at("/body").stringValue()

            assertEquals(tree(format), upstreamBody(requestId).get("response_format"))
            assertTrue(""""response_format":$format""" in raw) { raw }
        }
    }

    @Test
    fun `an injected provider 400 gives 400 with the fixed message and the provider's code and param`() {
        scenario(
            """{"otherwise":{"error":{"status":400,"message":"$PROVIDER_REASON","code":"invalid_json_schema",
            "param":"response_format.json_schema.schema"}}}""",
        )

        listOf(formatted(JSON_SCHEMA), formatted(JSON_SCHEMA, STREAMING)).forEach { body ->
            val result = chat(body).result()
            val error = tree(checkNotNull(result.responseBody)).get("error")

            assertEquals(400, result.status.value()) { result.responseBody }
            assertEquals(FIXED_MESSAGE, error.get("message").stringValue())
            assertEquals("invalid_json_schema", error.get("code").stringValue())
            assertEquals("response_format.json_schema.schema", error.get("param").stringValue())
        }
    }

    @ParameterizedTest
    @CsvSource("'', 7", "abc, 8")
    fun `with usage omitted a tool reply is estimated from its name and arguments, on both forms`(
        text: String,
        tokens: Int,
    ) {
        scenario(
            """{"otherwise":{"usage":{"omit":true},"content":{"mode":"fixed","text":"$text","toolCalls":[$PARIS]}}}""",
        )

        val complete = chat(MINIMAL).result()
        val last = rawStream(asking()).chunks().last()

        assertEquals("true", complete.responseHeaders.getFirst("x-gateway-usage-estimated"))
        assertEquals(tokens, tree(checkNotNull(complete.responseBody)).at("/usage/completion_tokens").intValue())
        assertTrue(last.at("/llmgw/usage_estimated").booleanValue()) { last.toString() }
        assertEquals(tokens, last.at("/usage/completion_tokens").intValue())
    }

    private companion object {
        const val USER = """{"role":"user","content":"Weather in Paris?"}"""
        const val WEATHER =
            """{"type":"function","function":{"name":"get_weather","description":"Weather","strict":true,
            "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],
            "additionalProperties":false}}}"""
        const val CHOICE = """{"type":"function","function":{"name":"get_weather"}}"""
        const val PARIS_ARGUMENTS = """{"city":"Paris"}"""
        const val PARIS = """{"id":"call_1","name":"get_weather","input":$PARIS_ARGUMENTS,"chunkChars":3}"""
        const val SUNNY = """{"content":{"mode":"fixed","text":"Sunny.","toolCalls":[]}}"""
        const val ONE_TOOL =
            """{"steps":[{"content":{"mode":"fixed","text":"","toolCalls":[$PARIS]}}],"otherwise":$SUNNY}"""
        const val TWO_TOOLS =
            """{"steps":[{"content":{"mode":"fixed","text":"","toolCalls":[$PARIS,
            {"id":"call_2","name":"get_time","input":{"zone":"CET"},"chunkChars":3}]}}],"otherwise":$SUNNY}"""
        const val ANSWER_1 = """{"role":"tool","content":"18C","tool_call_id":"call_1"}"""
        const val ANSWER_2 = """{"role":"tool","content":"12:00","tool_call_id":"call_2"}"""
        const val STREAMING = ""","stream":true"""
        const val TEXT = """{"type":"text"}"""
        const val JSON_OBJECT = """{"type":"json_object"}"""
        const val JSON_SCHEMA =
            """{"type":"json_schema","json_schema":{"name":"reply","strict":true,"schema":{"type":"object",""" +
                """"properties":{"a":{"type":"number","default":1.10}},"additionalProperties":false}}}"""
        const val PROVIDER_REASON = "schema too complex"
        const val FIXED_MESSAGE = "The provider rejected the request; see the code and param members."
    }
}
