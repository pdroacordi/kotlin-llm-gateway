package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkDelta
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ChatChunkWriterTest {
    private val mapper = JsonMapper.builder().build()

    private fun written(
        chunk: ChatChunk,
        includeUsage: Boolean = false,
    ): String = mapper.writeValueAsString(ChatChunkWriter.write(chunk, includeUsage, mapper.nodeFactory))

    private fun chunk(
        choice: ChunkChoice,
        usage: Usage? = null,
        extras: JsonValue.Object = JsonValue.Object.EMPTY,
    ) = ChatChunk("c", 1, ModelName("m"), listOf(choice), usage, extras)

    @Test
    fun `typed members come first, then extras in the order received`() {
        val delta = ChunkDelta("Hi", JsonValue.Object(mapOf("role" to JsonValue.Text("assistant"))))
        val choiceExtras = JsonValue.Object(mapOf("logprobs" to JsonValue.Null, "finish_reason" to JsonValue.Null))
        val extras = JsonValue.Object(mapOf("system_fingerprint" to JsonValue.Text("fp")))

        assertEquals(
            """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,""" +
                """"delta":{"content":"Hi","role":"assistant"},"logprobs":null,"finish_reason":null}],""" +
                """"usage":null,"system_fingerprint":"fp"}""",
            written(chunk(ChunkChoice(0, delta, extras = choiceExtras), extras = extras), includeUsage = true),
        )
    }

    @Test
    fun `without include_usage there is no usage member, even on a chunk that reports it`() {
        val json = written(chunk(ChunkChoice(0, ChunkDelta("Hi")), USAGE))

        assertFalse("usage" in json) { json }
    }

    @Test
    fun `with include_usage a chunk with usage writes it, and no extension member when it is the provider's`() {
        val usageChunk = ChatChunk("c", 1, ModelName("m"), emptyList(), USAGE)

        assertEquals(
            """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],""" +
                """"usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16}}""",
            written(usageChunk, includeUsage = true),
        )
    }

    @Test
    fun `an estimated usage carries the extension member`() {
        val usageChunk = ChatChunk("c", 1, ModelName("m"), emptyList(), USAGE.copy(estimated = true))

        assertEquals(
            """{"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],""" +
                """"usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16},""" +
                """"${ChatChunkWriter.EXTENSION_MEMBER}":{"usage_estimated":true}}""",
            written(usageChunk, includeUsage = true),
        )
    }

    @Test
    fun `presence is preserved - an absent content stays absent and a null stays null`() {
        val absent = written(chunk(ChunkChoice(0, ChunkDelta())))
        val nullContent =
            written(chunk(ChunkChoice(0, ChunkDelta(null, JsonValue.Object(mapOf("content" to JsonValue.Null))))))

        assertEquals("""{"index":0,"delta":{}}""", choiceOf(absent))
        assertEquals("""{"index":0,"delta":{"content":null}}""", choiceOf(nullContent))
    }

    @Test
    fun `an absent finish_reason stays absent and a typed one follows the extras`() {
        val extras = JsonValue.Object(mapOf("logprobs" to JsonValue.Null))
        val json = written(chunk(ChunkChoice(0, ChunkDelta(), FinishReason("stop"), extras)))

        assertEquals("""{"index":0,"delta":{},"logprobs":null,"finish_reason":"stop"}""", choiceOf(json))
    }

    private fun choiceOf(json: String): String = mapper.readTree(json).at("/choices/0").toString()

    private companion object {
        val USAGE = Usage(TokenCount(14), TokenCount(2))
    }
}
