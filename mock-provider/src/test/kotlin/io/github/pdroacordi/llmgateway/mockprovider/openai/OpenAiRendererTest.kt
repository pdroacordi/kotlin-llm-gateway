package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.Golden
import io.github.pdroacordi.llmgateway.mockprovider.generation.Finish
import io.github.pdroacordi.llmgateway.mockprovider.generation.PlanPart
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper

class OpenAiRendererTest {
    private val renderer = OpenAiRenderer(JsonMapper.builder().build())
    private val plan = Golden.plan("mock-chat")

    @Test
    fun `the non-stream completion matches the golden body`() {
        assertEquals(
            Golden.read("openai-chat.json"),
            renderer.completion(Golden.SEQ, Golden.CREATED, "mock-chat", plan),
        )
    }

    @Test
    fun `the stream without include_usage matches the golden bytes`() {
        val reply = renderer.stream(Golden.SEQ, Golden.CREATED, "mock-chat", plan, includeUsage = false)

        assertEquals(Golden.read("openai-chat-stream.sse"), reply.concatenated())
        assertEquals(2, reply.pieces.size)
    }

    @Test
    fun `the stream with include_usage matches the golden bytes`() {
        val reply = renderer.stream(Golden.SEQ, Golden.CREATED, "mock-chat", plan, includeUsage = true)

        assertEquals(Golden.read("openai-chat-stream-include-usage.sse"), reply.concatenated())
    }

    @Test
    fun `usage omit drops the usage member and the usage chunk, chunks keep usage null`() {
        val omitted = plan.copy(usage = plan.usage.copy(omit = true))
        val body = renderer.completion(Golden.SEQ, Golden.CREATED, "mock-chat", omitted)
        val stream = renderer.stream(Golden.SEQ, Golden.CREATED, "mock-chat", omitted, includeUsage = true)

        assertFalse("\"usage\"" in body) { body }
        assertFalse("prompt_tokens" in stream.concatenated())
        // Four chunks (role, two pieces, finish), each with "usage":null.
        assertEquals(5, stream.concatenated().split("\"usage\":null").size)
    }

    @Test
    fun `cached tokens are reported in prompt_tokens_details, never above the prompt`() {
        val cached = plan.copy(usage = plan.usage.copy(cachedTokens = 4))

        assertEquals(
            true,
            """"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":4}"""
                in renderer.completion(1, 0, "m", cached),
        )
    }

    @Test
    fun `a length finish`() {
        val cut = plan.copy(finish = io.github.pdroacordi.llmgateway.mockprovider.generation.Finish.LENGTH)

        assertEquals(true, "\"finish_reason\":\"length\"" in renderer.completion(1, 0, "m", cut))
    }

    @Test
    fun `embeddings in float and base64, with and without usage`() {
        val vector = floatArrayOf(0.5f, -0.25f)

        assertEquals(
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.5,-0.25]}],"model":"e",""" +
                """"usage":{"prompt_tokens":2,"total_tokens":2}}""",
            renderer.embeddings("e", listOf(vector), base64 = false, promptTokens = 2),
        )
        assertEquals(
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":"AAAAPwAAgL4="}],"model":"e"}""",
            renderer.embeddings("e", listOf(vector), base64 = true, promptTokens = null),
        )
    }

    @Test
    fun `the models list`() {
        assertEquals(
            """{"object":"list","data":[{"id":"a","object":"model","created":5,"owned_by":"mock-provider"},""" +
                """{"id":"b","object":"model","created":5,"owned_by":"mock-provider"}]}""",
            renderer.models(listOf("a", "b"), 5),
        )
    }

    @Test
    fun `the error envelope has exactly four members and escapes its message`() {
        assertEquals(
            """{"error":{"message":"say \"hi\"","type":"requests","param":null,"code":"rate_limit_exceeded"}}""",
            renderer.error(ErrorBody(429, "requests", "rate_limit_exceeded", "say \"hi\"", null)),
        )
    }

    @Test
    fun `the in-band stream error is a data event with the four members and no done sentinel`() {
        assertEquals(
            "data: {\"error\":{\"message\":\"m\",\"type\":\"server_error\",\"param\":null,\"code\":null}}\n\n",
            renderer
                .stream(
                    1,
                    0,
                    "m",
                    plan,
                    includeUsage = false,
                ).errorEvent(ErrorBody(500, "server_error", null, "m", null)),
        )
    }

    @Test
    fun `tool calls match the golden body`() {
        assertEquals(
            Golden.read("openai-tools.json"),
            renderer.completion(Golden.SEQ, Golden.CREATED, "mock-chat", Golden.toolPlan("mock-chat")),
        )
    }

    @Test
    fun `tool calls match the golden stream, and thinking is ignored`() {
        val reply = renderer.stream(Golden.SEQ, Golden.CREATED, "mock-chat", Golden.toolPlan("mock-chat"), false)

        assertEquals(Golden.read("openai-tools-stream.sse"), reply.concatenated())
        assertFalse("sig-1" in reply.concatenated())
    }

    @Test
    fun `no text and a tool call give null content`() {
        val tools = Golden.toolPlan("mock-chat")
        val only = tools.copy(parts = tools.parts.filterIsInstance<PlanPart.ToolCall>())
        val body = renderer.completion(1, 2, "m", only)
        val stream = renderer.stream(1, 2, "m", only, includeUsage = false).concatenated()

        assertTrue(""""content":null,"tool_calls":[""" in body) { body }
        assertTrue(""""role":"assistant","content":null""" in stream) { stream }
    }

    @ParameterizedTest
    @CsvSource(
        "TOOL_CALLS, tool_calls",
        "STOP_SEQUENCE, stop",
        "REFUSAL, content_filter",
        "PAUSE_TURN, length",
        "CONTEXT_WINDOW, length",
    )
    fun `each finish has its finish reason`(
        finish: Finish,
        finishReason: String,
    ) {
        val body = renderer.completion(1, 2, "m", plan.copy(finish = finish))

        assertTrue(""""finish_reason":"$finishReason"""" in body) { body }
    }
}
