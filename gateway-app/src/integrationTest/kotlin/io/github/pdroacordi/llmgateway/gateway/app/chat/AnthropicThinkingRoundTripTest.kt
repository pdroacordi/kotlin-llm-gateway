package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AnthropicThinkingRoundTripTest : AnthropicBackedTest() {
    @Test
    fun `thinking stays out of content and comes back in llmgw, in order, on both forms`() {
        claudeScenario(THINKING)

        val message = answer().body.at("/choices/0/message")
        val chunks = rawStream(CLAUDE_STREAM).chunks()
        val finish = chunks.last { !it.get("choices").isEmpty }

        assertEquals("Hi", message.get("content").stringValue())
        assertEquals(BLOCKS, mapper.writeValueAsString(message.at("/llmgw/thinking_blocks")))
        assertEquals(BLOCKS, mapper.writeValueAsString(finish.at("/choices/0/delta/llmgw/thinking_blocks")))
        assertEquals(1, chunks.count { !it.at("/choices/0/delta/llmgw").isMissingNode })
        assertFalse(chunks.any { "sig-1" in it.at("/choices/0/delta/content").stringValue("") })
    }

    @Test
    fun `an assistant message echoing them reaches the journal with those blocks first, unchanged`() {
        claudeScenario(THINKING)

        val assistant = mapper.writeValueAsString(answer().body.at("/choices/0/message"))
        val body =
            """{"model":"mock-claude","messages":[{"role":"user","content":"a"},$assistant,
            {"role":"user","content":"b"}]}"""
        val blocks = upstreamBody(answer(body).requestId).at("/messages/1/content")

        assertEquals("thinking", blocks.get(0).get("type").stringValue())
        assertEquals("sig-1", blocks.get(0).get("signature").stringValue())
        assertEquals("redacted_thinking", blocks.get(1).get("type").stringValue())
        assertEquals("opaque-1", blocks.get(1).get("data").stringValue())
        assertEquals("text", blocks.get(2).get("type").stringValue())
    }

    @Test
    fun `llmgw on a message never reaches the OpenAI upstream`() {
        val body =
            """{"model":"mock-chat","messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b",
            "llmgw":{"thinking_blocks":[{"type":"thinking","thinking":"","signature":"sig-1"}]}},
            {"role":"user","content":"c"}]}"""

        val sent = upstreamBody(answer(body).requestId)

        assertEquals(3, sent.get("messages").size())
        assertTrue(sent.at("/messages/1/llmgw").isMissingNode) { sent.toString() }
    }

    @Test
    fun `a thinking-only prefix commits nothing, so an in-band error after it is its status, not a frame`() {
        claudeScenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"Hi","thinking":[{"signature":"sig-1"}]},
            "fault":{"kind":"stream_error","afterPieces":0}}}""",
        )

        val result = chat(CLAUDE_STREAM).result()

        assertEquals(503, result.status.value())
        assertEquals(
            "provider_unavailable",
            mapper
                .readTree(result.responseBody)
                .at("/error/code")
                .stringValue(),
        )
    }

    private companion object {
        const val THINKING =
            """{"otherwise":{"content":{"mode":"fixed","text":"Hi",
            "thinking":[{"signature":"sig-1"},{"redacted":"opaque-1"}]}}}"""
        const val BLOCKS =
            """[{"type":"thinking","thinking":"","signature":"sig-1"},""" +
                """{"type":"redacted_thinking","data":"opaque-1"}]"""
    }
}
