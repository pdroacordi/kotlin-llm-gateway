package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ChatChunkTest {
    private val extras = JsonValue.Object(mapOf("role" to JsonValue.Text(CANARY)))
    private val delta = ChunkDelta(CANARY, extras)
    private val choice = ChunkChoice(0, delta, FinishReason("stop"), extras)
    private val chunk = ChatChunk("chatcmpl-1", 1, ModelName("m"), listOf(choice), extras = extras)

    @Test
    fun `no toString prints a delta or an extra`() {
        val printed =
            listOf(
                chunk,
                choice,
                delta,
                StreamOptions(includeUsage = true, extras = extras),
                ProviderStreamEvent.Chunk(chunk),
                ChatStreamEvent.Chunk(chunk),
            )

        printed.forEach { assertFalse(CANARY in it.toString()) { it.toString() } }
    }

    @Test
    fun `a chunk prints its model and counts`() {
        assertEquals("ChatChunk(model=m, choices=1, usage=false)", chunk.toString())
        assertEquals("ChunkChoice(index=0)", choice.toString())
        assertEquals("ChunkDelta(length=${CANARY.length})", delta.toString())
        assertEquals("ChunkDelta(length=null)", ChunkDelta().toString())
        assertEquals("StreamOptions(includeUsage=true, extras=1)", StreamOptions(true, extras).toString())
    }

    private companion object {
        const val CANARY = "canary-completion"
    }
}
