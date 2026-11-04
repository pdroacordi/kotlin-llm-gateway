package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import java.time.Instant

class AnthropicChatEndToEndTest : AnthropicBackedTest() {
    private fun extra(members: String): String =
        """{"model":"mock-claude",$members,"messages":[{"role":"user","content":"Hi"}]}"""

    @Test
    fun `a minimal request gives a chat completion in the OpenAI shape`() {
        val before = Instant.now().epochSecond
        val reply = answer()
        val body = reply.body

        assertEquals("chat.completion", body.get("object").stringValue())
        assertTrue(body.get("id").stringValue().startsWith("msg_mock_")) { body.toString() }
        assertTrue(body.get("created").longValue() >= before) { body.toString() }
        assertEquals("assistant", body.at("/choices/0/message/role").stringValue())
        assertEquals("stop", body.at("/choices/0/finish_reason").stringValue())
        assertTrue(body.at("/choices/0/message/refusal").isNull)
        assertTrue(body.at("/choices/0/logprobs").isNull)
    }

    @Test
    fun `the journal shows the key by fingerprint, the version, the cap, the request ID and no Authorization`() {
        val result = chat(CLAUDE_MINIMAL).result()
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        val entry = journalOf(requestId).single()

        assertEquals("anthropic", result.responseHeaders.getFirst("x-gateway-deployment"))
        assertEquals("anthropic.messages", entry.at("/route").stringValue())
        assertEquals("x-api-key", entry.at("/credential/scheme").stringValue())
        assertEquals(fingerprint(ANTHROPIC_KEY), entry.at("/credential/fingerprint").stringValue())
        assertEquals("2023-06-01", entry.at("/headers/anthropic-version/0").stringValue())
        assertTrue(entry.at("/headers/authorization").isMissingNode) { entry.toString() }
        assertEquals(4096, upstreamBody(requestId).get("max_tokens").intValue())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """"seed":1 | unsupported_parameter | seed""",
            """"response_format":{"type":"text"} | unsupported_parameter | response_format""",
            """"thinking":{"type":"disabled"} | unsupported_parameter | thinking""",
            """"n":2 | unsupported_value | n""",
            """"logprobs":true | unsupported_value | logprobs""",
            """"presence_penalty":0.5 | unsupported_value | presence_penalty""",
            """"service_tier":"flex" | unsupported_value | service_tier""",
            """"stop":7 | unsupported_value | stop""",
            """"tool_choice":{"type":"custom"} | unsupported_value | tool_choice""",
        ],
    )
    fun `a rejected member gives 400 with its code and param, and no upstream call`(
        member: String,
        code: String,
        param: String,
    ) {
        val before = lastSeq()

        listOf(extra(member), extra("$member,\"stream\":true")).forEach { body ->
            val result = chat(body).result()
            val error = mapper.readTree(result.responseBody).get("error")

            assertEquals(400, result.status.value()) { result.responseBody }
            assertEquals("invalid_request_error", error.get("type").stringValue())
            assertEquals(code, error.get("code").stringValue())
            assertEquals(param, error.get("param").stringValue())
        }
        assertEquals(before, lastSeq())
    }

    @Test
    fun `neutral and ignored members cost nothing`() {
        val body = extra(""""n":1,"logprobs":false,"frequency_penalty":0.0,"store":true,"metadata":{"a":"b"}""")

        val sent = upstreamBody(answer(body).requestId)

        assertFalse("n" in sent.propertyNames())
        assertNull(sent.get("store"))
        assertNull(sent.get("metadata"))
    }

    @Test
    fun `temperature and top_p absent stay absent, sent they keep their literal`() {
        val plain = upstreamBody(answer().requestId)
        val sampled = journalOf(answer(extra(""""temperature":0.20,"top_p":1.0""")).requestId).single()
        val body = sampled.at("/body").stringValue()

        assertNull(plain.get("temperature"))
        assertNull(plain.get("top_p"))
        assertTrue(""""temperature":0.20""" in body) { body }
        assertTrue(""""top_p":1.0""" in body) { body }
    }

    @Test
    fun `an injected provider 400 for a sampling member is upstream_rejected_request`() {
        claudeScenario("""{"otherwise":{"error":{"status":400,"message":"temperature is deprecated"}}}""")

        val result = chat(extra(""""temperature":0.2""")).result()

        assertEquals(400, result.status.value())
        assertEquals(
            "upstream_rejected_request",
            mapper
                .readTree(result.responseBody)
                .at("/error/code")
                .stringValue(),
        )
    }

    @Test
    fun `usage is normalised, identically on complete and stream`() {
        claudeScenario(
            """{"otherwise":{"usage":{"promptTokens":60,"cachedTokens":20,"cacheCreationTokens":30,
            "completionTokens":5}}}""",
        )
        val complete = answer().body.get("usage")
        val stream =
            rawStream(CLAUDE_STREAM.dropLast(1) + ""","stream_options":{"include_usage":true}}""")
                .chunks()
                .last()
                .get("usage")

        listOf(complete, stream).forEach(::assertUsage)
        assertEquals(complete, stream)
    }

    private fun assertUsage(usage: JsonNode) {
        assertEquals(60, usage.get("prompt_tokens").intValue())
        assertEquals(5, usage.get("completion_tokens").intValue())
        assertEquals(65, usage.get("total_tokens").intValue())
        assertEquals(20, usage.at("/prompt_tokens_details/cached_tokens").intValue())
        assertEquals(30, usage.get("cache_creation_input_tokens").intValue())
    }

    @Test
    fun `usage omit is estimated and flagged, on a stream with the llmgw flag`() {
        claudeScenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val header = chat(CLAUDE_MINIMAL).result().responseHeaders.getFirst("x-gateway-usage-estimated")
        val last =
            rawStream(
                CLAUDE_STREAM.dropLast(1) + ""","stream_options":{"include_usage":true}}""",
            ).chunks().last()

        assertEquals("true", header)
        assertTrue(last.at("/llmgw/usage_estimated").booleanValue()) { last.toString() }
    }

    @Test
    fun `every chunk has the OpenAI member set, one id and one created, and the first delta has the role`() {
        claudeScenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val stream = rawStream(CLAUDE_STREAM)
        val chunks = stream.chunks()

        assertEquals("anthropic", stream.header("x-gateway-deployment"))
        assertEquals("data: [DONE]", stream.frames.last())
        assertEquals(1, chunks.map { it.get("id") }.toSet().size)
        assertEquals(1, chunks.map { it.get("created") }.toSet().size)
        assertEquals("assistant", chunks.first().at("/choices/0/delta/role").stringValue())
        chunks.forEach { chunk ->
            assertEquals("chat.completion.chunk", chunk.get("object").stringValue())
            assertEquals(CLAUDE, chunk.get("model").stringValue())
            val choice = chunk.at("/choices/0")
            assertEquals(setOf("index", "delta", "logprobs", "finish_reason"), choice.propertyNames().toSet())
        }
        assertEquals("stop", chunks.last().at("/choices/0/finish_reason").stringValue())
    }

    @Test
    fun `include_usage true gives one chunk with empty choices, absent gives none, and DONE ends both`() {
        val asked = rawStream(CLAUDE_STREAM.dropLast(1) + ""","stream_options":{"include_usage":true}}""")
        val silent = rawStream(CLAUDE_STREAM)

        assertEquals(1, asked.chunks().count { it.get("choices").isEmpty })
        assertEquals(0, silent.chunks().count { it.get("choices").isEmpty })
        assertEquals("data: [DONE]", asked.frames.last())
        assertEquals("data: [DONE]", silent.frames.last())
    }

    @ParameterizedTest
    @CsvSource(
        "stop_sequence, stop",
        "refusal, content_filter",
        "pause_turn, length",
        "context_window, length",
    )
    fun `a scripted stop reason reaches the client as its finish_reason, on both forms`(
        finish: String,
        finishReason: String,
    ) {
        claudeScenario("""{"otherwise":{"content":{"mode":"fixed","text":"x","finish":"$finish"}}}""")

        assertEquals(finishReason, answer().body.at("/choices/0/finish_reason").stringValue())
        assertEquals(
            finishReason,
            rawStream(CLAUDE_STREAM)
                .chunks()
                .last()
                .at("/choices/0/finish_reason")
                .stringValue(),
        )
    }
}
