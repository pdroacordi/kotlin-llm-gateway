package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class OpenAiChunkReaderTest {
    private val reader = OpenAiChunkReader(JsonMapper.builder().build().exactTreeReader())

    private fun read(json: String): ChunkRead = reader.read(json.encodeToByteArray())

    private fun chunk(json: String): ChatChunk = (read(json) as ChunkRead.Read).chunk

    private fun withDelta(delta: String): String =
        """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m",
           "choices":[{"index":0,"delta":$delta,"logprobs":null,"finish_reason":null}]}"""

    @Test
    fun `a golden opening chunk keeps every member where it belongs`() {
        val chunk = chunk(OPENING)

        assertEquals("chatcmpl-mock-000000000001", chunk.id)
        assertEquals(1793750400, chunk.created)
        assertEquals("mock-chat", chunk.model.value)
        assertEquals(JsonValue.Object(mapOf("system_fingerprint" to JsonValue.Text("fp_mock"))), chunk.extras)
        val choice = chunk.choices.single()
        assertEquals(0, choice.index)
        assertEquals("", choice.delta.content)
        assertEquals(JsonValue.Object(mapOf("role" to JsonValue.Text("assistant"))), choice.delta.extras)
        assertNull(choice.finishReason)
        assertEquals(
            JsonValue.Object(mapOf("logprobs" to JsonValue.Null, "finish_reason" to JsonValue.Null)),
            choice.extras,
        )
    }

    @Test
    fun `a finish chunk has a typed finish reason and no finish_reason in extras`() {
        val choice =
            chunk(
                """{"id":"c","created":1,"model":"m",
                   "choices":[{"index":2,"delta":{},"logprobs":null,"finish_reason":"stop"}]}""",
            ).choices.single()

        assertEquals("stop", choice.finishReason?.value)
        assertEquals(2, choice.index)
        assertEquals(JsonValue.Object(mapOf("logprobs" to JsonValue.Null)), choice.extras)
    }

    @Test
    fun `a golden usage chunk has empty choices and a typed usage`() {
        val chunk =
            chunk(
                """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],
                   "usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,
                   "prompt_tokens_details":{"cached_tokens":3}}}""",
            )

        assertTrue(chunk.choices.isEmpty())
        assertEquals(TokenCount(14), chunk.usage?.inputTokens)
        assertEquals(TokenCount(2), chunk.usage?.outputTokens)
        assertEquals(TokenCount(3), chunk.usage?.cachedInputTokens)
    }

    @Test
    fun `usage null, absent and unreadable give no usage and never sit in extras`() {
        val base = """"id":"c","created":1,"model":"m","choices":[]"""

        listOf("{$base,\"usage\":null}", "{$base}", "{$base,\"usage\":{\"prompt_tokens\":\"x\"}}").forEach { json ->
            val chunk = chunk(json)
            assertNull(chunk.usage) { json }
            assertFalse("usage" in chunk.extras.members) { json }
        }
    }

    @Test
    fun `content string, null and absent stay as they came`() {
        val text = chunk(withDelta("""{"content":"Hi"}""")).choices.single().delta
        val nullContent = chunk(withDelta("""{"content":null}""")).choices.single().delta
        val absent = chunk(withDelta("""{}""")).choices.single().delta

        assertEquals("Hi", text.content)
        assertEquals(JsonValue.Object.EMPTY, text.extras)
        assertNull(nullContent.content)
        assertEquals(JsonValue.Object(mapOf("content" to JsonValue.Null)), nullContent.extras)
        assertNull(absent.content)
        assertEquals(JsonValue.Object.EMPTY, absent.extras)
    }

    @Test
    fun `a content of another type stays in extras verbatim`() {
        val delta = chunk(withDelta("""{"content":5}""")).choices.single().delta

        assertNull(delta.content)
        assertEquals(JsonValue.Object(mapOf("content" to JsonValue.Number("5"))), delta.extras)
    }

    @Test
    fun `a finish_reason of another type stays in extras verbatim`() {
        val choice =
            chunk("""{"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":5}]}""")
                .choices
                .single()

        assertNull(choice.finishReason)
        assertEquals(JsonValue.Number("5"), choice.extras.members["finish_reason"])
    }

    @Test
    fun `a tool_calls delta and obfuscation stay verbatim in extras`() {
        val delta =
            chunk(
                withDelta(
                    """{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"f","arguments":"{\"a\":1}"}}],
                       "obfuscation":"x1"}""",
                ),
            ).choices.single().delta

        assertEquals(JsonValue.Text("x1"), delta.extras.members["obfuscation"])
        val call = (delta.extras.members["tool_calls"] as JsonValue.Array).items.single() as JsonValue.Object
        assertEquals(JsonValue.Text("call_1"), call.members["id"])
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"created":1,"model":"m","choices":[]}""",
            """{"id":5,"created":1,"model":"m","choices":[]}""",
            """{"id":"c","model":"m","choices":[]}""",
            """{"id":"c","created":"1","model":"m","choices":[]}""",
            """{"id":"c","created":1.5,"model":"m","choices":[]}""",
            """{"id":"c","created":1,"choices":[]}""",
            """{"id":"c","created":1,"model":" ","choices":[]}""",
            """{"id":"c","created":1,"model":5,"choices":[]}""",
            """{"id":"c","created":1,"model":"m"}""",
            """{"id":"c","created":1,"model":"m","choices":{}}""",
            """{"id":"c","created":1,"model":"m","choices":[5]}""",
            """{"id":"c","created":1,"model":"m","choices":[{"index":0}]}""",
            """{"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":5}]}""",
            """[1]""",
            """{not json""",
            "",
        ],
    )
    fun `each missing or mistyped required member is Unreadable`(json: String) {
        assertEquals(ChunkRead.Unreadable, read(json))
    }

    @Test
    fun `an error member is InBandError with the checked fields and no message`() {
        val read =
            read("""{"error":{"message":"canary-provider-message","type":"server_error","param":null,"code":"x_y"}}""")

        assertEquals(ChunkRead.InBandError(ProviderErrorFields("x_y", "server_error", null)), read)
        assertFalse("canary" in read.toString())
    }

    @Test
    fun `an error that is not an object is still InBandError, with no fields`() {
        assertEquals(ChunkRead.InBandError(ProviderErrorFields.NONE), read("""{"error":"overloaded"}"""))
    }

    @Test
    fun `a null error member is not an error, and stays in extras`() {
        val chunk = chunk("""{"id":"c","created":1,"model":"m","choices":[],"error":null}""")

        assertEquals(JsonValue.Null, chunk.extras.members["error"])
    }

    @Test
    fun `empty choices are readable`() {
        assertTrue(chunk("""{"id":"c","created":1,"model":"m","choices":[]}""").choices.isEmpty())
    }

    @Test
    fun `a choice with no integer index reads as 0, as a completion does`() {
        val choice = chunk("""{"id":"c","created":1,"model":"m","choices":[{"delta":{}}]}""").choices.single()

        assertEquals(0, choice.index)
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.5", "null", "\"1\"", "1e99"])
    fun `an index that is not an integer reads as 0, and does not throw`(index: String) {
        val chunk = chunk("""{"id":"c","created":1,"model":"m","choices":[{"index":$index,"delta":{}}]}""")

        assertEquals(0, chunk.choices.single().index)
    }

    private companion object {
        const val OPENING =
            """{"id":"chatcmpl-mock-000000000001","object":"chat.completion.chunk","created":1793750400,""" +
                """"model":"mock-chat","system_fingerprint":"fp_mock","choices":[{"index":0,""" +
                """"delta":{"role":"assistant","content":""},"logprobs":null,"finish_reason":null}]}"""
    }
}
