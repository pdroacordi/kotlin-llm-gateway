package io.github.pdroacordi.llmgateway.mockprovider.anthropic

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

class AnthropicRendererTest {
    private val renderer = AnthropicRenderer(JsonMapper.builder().build())
    private val plan = Golden.plan("mock-claude")

    @Test
    fun `the non-stream message matches the golden body`() {
        assertEquals(Golden.read("anthropic-messages.json"), renderer.message(Golden.SEQ, "mock-claude", plan))
    }

    @Test
    fun `the stream matches the golden bytes`() {
        val reply = renderer.stream(Golden.SEQ, "mock-claude", plan)

        assertEquals(Golden.read("anthropic-messages-stream.sse"), reply.concatenated())
        assertEquals(3, reply.opening.size)
        assertEquals(2, reply.pieces.size)
    }

    @Test
    fun `cache fields split the input`() {
        val cached = plan.copy(usage = plan.usage.copy(cachedTokens = 4, cacheCreationTokens = 6))
        val body = renderer.message(1, "m", cached)

        assertTrue(
            """"usage":{"input_tokens":4,"cache_creation_input_tokens":6,"cache_read_input_tokens":4,""" +
                """"cache_creation":{"ephemeral_5m_input_tokens":6,"ephemeral_1h_input_tokens":0}""" in body,
        ) { body }
    }

    @Test
    fun `usage omit drops usage from the body, message_start and message_delta`() {
        val omitted = plan.copy(usage = plan.usage.copy(omit = true))

        assertFalse("usage" in renderer.message(1, "m", omitted))
        assertFalse("usage" in renderer.stream(1, "m", omitted).concatenated())
    }

    @Test
    fun `max_tokens stop reason and empty content`() {
        val empty = plan.copy(parts = emptyList(), finish = Finish.LENGTH)
        val body = renderer.message(1, "m", empty)

        assertTrue(""""content":[],"stop_reason":"max_tokens"""" in body) { body }
    }

    @Test
    fun `the error body carries type error and the request id`() {
        assertEquals(
            """{"type":"error","error":{"type":"rate_limit_error","message":"slow"},"request_id":"req_1"}""",
            renderer.error(ErrorBody(429, "rate_limit_error", null, "slow", null), "req_1"),
        )
    }

    @Test
    fun `the in-band stream error is an error event`() {
        assertEquals(
            "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"m\"}}\n\n",
            renderer.stream(1, "m", plan).errorEvent(ErrorBody(529, "overloaded_error", null, "m", null)),
        )
    }

    @Test
    fun `thinking, text and tool calls match the golden body`() {
        assertEquals(
            Golden.read("anthropic-tools.json"),
            renderer.message(Golden.SEQ, "mock-claude", Golden.toolPlan("mock-claude")),
        )
    }

    @Test
    fun `thinking, text and tool calls match the golden stream`() {
        val reply = renderer.stream(Golden.SEQ, "mock-claude", Golden.toolPlan("mock-claude"))

        assertEquals(Golden.read("anthropic-tools-stream.sse"), reply.concatenated())
    }

    @Test
    fun `a piece keeps one flushed frame per event, and a thinking prefix sits in the opening`() {
        val reply = renderer.stream(Golden.SEQ, "mock-claude", Golden.toolPlan("mock-claude"))

        assertTrue(reply.opening.any { "signature_delta" in it })
        assertTrue(reply.opening.last().startsWith("event: ping"))
        assertEquals(
            1,
            reply.pieces
                .first()
                .frames.size,
        )
        assertTrue(reply.pieces.any { it.frames.size > 1 })
        assertTrue(reply.pieces.flatMap { it.frames }.all { it.endsWith("\n\n") })
    }

    @Test
    fun `a thinking-only plan puts the whole prefix in the opening`() {
        val thinking = plan.copy(parts = listOf(PlanPart.Thinking("sig", null)))
        val reply = renderer.stream(1, "m", thinking)

        assertTrue(reply.pieces.isEmpty())
        assertTrue(reply.opening.any { "signature_delta" in it })
    }

    @ParameterizedTest
    @CsvSource(
        "TOOL_CALLS, tool_use",
        "STOP_SEQUENCE, stop_sequence",
        "REFUSAL, refusal",
        "PAUSE_TURN, pause_turn",
        "CONTEXT_WINDOW, model_context_window_exceeded",
    )
    fun `each finish has its stop reason`(
        finish: Finish,
        stopReason: String,
    ) {
        val stopped = plan.copy(finish = finish)
        val expected = """"stop_reason":"$stopReason""""

        assertTrue(expected in renderer.message(1, "m", stopped))
        assertTrue(expected in renderer.stream(1, "m", stopped).concatenated())
    }

    @Test
    fun `a code renders as error details, in both the body and the stream event`() {
        val error = ErrorBody(429, "rate_limit_error", "enforced_spend_limit_reached", "m", null)
        val details = """"details":{"error_code":"enforced_spend_limit_reached"}"""

        assertTrue(details in renderer.error(error, "req_1"))
        assertTrue(details in renderer.stream(1, "m", plan).errorEvent(error))
    }
}
