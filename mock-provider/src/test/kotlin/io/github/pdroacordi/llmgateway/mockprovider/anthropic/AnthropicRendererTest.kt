package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.Golden
import io.github.pdroacordi.llmgateway.mockprovider.generation.Finish
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
        val empty = plan.copy(pieces = emptyList(), finish = Finish.LENGTH)
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
}
