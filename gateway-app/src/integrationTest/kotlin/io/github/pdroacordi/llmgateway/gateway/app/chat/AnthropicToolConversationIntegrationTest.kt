package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode

class AnthropicToolConversationIntegrationTest : AnthropicBackedTest() {
    private fun firstTurn(extra: String = ""): String =
        """{"model":"mock-claude","tools":[$WEATHER]$extra,
        "messages":[{"role":"user","content":"Weather in Paris?"}]}"""

    private fun secondTurn(
        assistant: String,
        results: String,
    ): String =
        """{"model":"mock-claude","tools":[$WEATHER],"messages":[
        {"role":"user","content":"Weather in Paris?"},$assistant,$results]}"""

    private fun assertToolTurn(body: JsonNode) {
        val assistant = body.at("/messages/1")
        val user = body.at("/messages/2")
        val toolUse = assistant.get("content").values().single { it.get("type").stringValue() == "tool_use" }

        assertEquals("assistant", assistant.get("role").stringValue())
        assertEquals("toolu_1", toolUse.get("id").stringValue())
        assertEquals("get_weather", toolUse.get("name").stringValue())
        assertEquals("Paris", toolUse.at("/input/city").stringValue())
        assertEquals("user", user.get("role").stringValue())
        assertEquals("tool_result", user.at("/content/0/type").stringValue())
        assertEquals("toolu_1", user.at("/content/0/tool_use_id").stringValue())
        assertEquals("18C", user.at("/content/0/content").stringValue())
    }

    @Test
    fun `a two-turn tool conversation round-trips on complete, echoing the reply whole`() {
        claudeScenario(ONE_TOOL)

        val first = answer(firstTurn())
        val message = first.body.at("/choices/0/message")
        val call = message.at("/tool_calls/0")

        assertEquals("tool_calls", first.body.at("/choices/0/finish_reason").stringValue())
        assertEquals("function", call.get("type").stringValue())
        assertEquals("""{"city":"Paris"}""", call.at("/function/arguments").stringValue())

        val results = """{"role":"tool","tool_call_id":"toolu_1","content":"18C"}"""
        val second = answer(secondTurn(mapper.writeValueAsString(message), results))

        assertEquals("Sunny.", second.body.at("/choices/0/message/content").stringValue())
        assertToolTurn(upstreamBody(second.requestId))
    }

    @Test
    fun `a two-turn tool conversation round-trips on stream`() {
        claudeScenario(ONE_TOOL)

        val stream = rawStream(firstTurn(""","stream":true"""))
        val call = stream.toolCalls().getValue(0)

        assertEquals("toolu_1", call.id)
        assertEquals("get_weather", call.name)
        assertEquals("""{"city":"Paris"}""", call.arguments.toString())
        val finish = stream.chunks().last()
        assertEquals("tool_calls", finish.at("/choices/0/finish_reason").stringValue())

        val assistant =
            """{"role":"assistant","content":"Checking.","tool_calls":[{"id":"${call.id}","type":"function",
            "function":{"name":"${call.name}","arguments":${mapper.writeValueAsString(call.arguments.toString())}}}]}"""
        val results = """{"role":"tool","tool_call_id":"toolu_1","content":"18C"}"""
        val second = rawStream(secondTurn(assistant, results).dropLast(1) + ""","stream":true}""")

        assertEquals(200, second.status) { second.body }
        assertEquals("data: [DONE]", second.frames.last())
        assertToolTurn(upstreamBody(second.requestId))
    }

    @Test
    fun `a wrongly ordered conversation fails the mock's check, so the check is live`() {
        claudeScenario(ONE_TOOL)
        val assistant =
            """{"role":"assistant","tool_calls":[{"id":"t","type":"function","function":{"name":"g","arguments":""}}]}"""

        val result = chat(secondTurn(assistant, """{"role":"user","content":"never mind"}""")).result()

        assertEquals(400, result.status.value()) { result.responseBody }
    }

    @Test
    fun `parallel calls, two tool messages and a user message become one user message, results first`() {
        claudeScenario(TWO_TOOLS)

        val first = answer(firstTurn())
        val calls =
            first.body
                .at("/choices/0/message/tool_calls")
                .values()
                .toList()
        val assistant = mapper.writeValueAsString(first.body.at("/choices/0/message"))
        val results =
            """{"role":"tool","tool_call_id":"${calls[0].get("id").stringValue()}","content":"a"},
            {"role":"tool","tool_call_id":"${calls[1].get("id").stringValue()}","content":"b"},
            {"role":"user","content":"and tomorrow?"}"""
        val second = answer(secondTurn(assistant, results))
        val user = upstreamBody(second.requestId).at("/messages/2/content")

        assertEquals(2, calls.size)
        assertEquals(listOf("tool_result", "tool_result", "text"), user.values().map { it.get("type").stringValue() })
    }

    @Test
    fun `on a stream tool indexes are 0 and 1 though Anthropic's block indexes are 1 and 2`() {
        claudeScenario(TWO_TOOLS)

        val calls = rawStream(firstTurn(""","stream":true""")).toolCalls()

        assertEquals(listOf(0, 1), calls.keys.toList())
        assertEquals("""{"city":"Paris"}""", calls.getValue(0).arguments.toString())
    }

    @Test
    fun `a tool with no input ends with an empty object, on both forms`() {
        claudeScenario(NO_INPUT)

        val complete = answer(firstTurn())
        val stream = rawStream(firstTurn(""","stream":true"""))

        assertEquals("{}", complete.body.at("/choices/0/message/tool_calls/0/function/arguments").stringValue())
        assertEquals(
            "{}",
            stream
                .toolCalls()
                .getValue(0)
                .arguments
                .toString(),
        )
        assertTrue(complete.body.at("/choices/0/message/content").isNull)
    }

    @Test
    fun `tool_choice and parallel_tool_calls reach the journal in Anthropic's shape`() {
        val body =
            firstTurn(
                ""","tool_choice":{"type":"function","function":{"name":"get_weather"}},"parallel_tool_calls":false""",
            )

        val sent = upstreamBody(answer(body).requestId)

        assertEquals("tool", sent.at("/tool_choice/type").stringValue())
        assertEquals("get_weather", sent.at("/tool_choice/name").stringValue())
        assertTrue(sent.at("/tool_choice/disable_parallel_tool_use").booleanValue())
        assertEquals("get_weather", sent.at("/tools/0/name").stringValue())
        assertEquals("object", sent.at("/tools/0/input_schema/type").stringValue())
        assertNull(sent.at("/tools/0").get("function"))
    }

    private companion object {
        const val WEATHER =
            """{"type":"function","function":{"name":"get_weather","description":"Weather",
            "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}"""
        const val SUNNY = """{"content":{"mode":"fixed","text":"Sunny.","toolCalls":[]}}"""
        const val PARIS = """{"id":"toolu_1","name":"get_weather","input":{"city":"Paris"},"chunkChars":5}"""
        const val ONE_TOOL =
            """{"steps":[{"content":{"mode":"fixed","text":"Checking.","toolCalls":[$PARIS]}}],"otherwise":$SUNNY}"""
        const val TWO_TOOLS =
            """{"steps":[{"content":{"mode":"fixed","text":"Checking.","toolCalls":[$PARIS,
            {"name":"get_time","input":{"zone":"CET"}}]}}],"otherwise":$SUNNY}"""
        const val NO_INPUT =
            """{"otherwise":{"content":{"mode":"fixed","text":"","toolCalls":[{"name":"now","input":{}}]}}}"""
    }
}
