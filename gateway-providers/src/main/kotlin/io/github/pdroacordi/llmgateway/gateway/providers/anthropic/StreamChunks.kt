package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkDelta
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

/**
 * The OpenAI chunks of one stream, under one [id], [model] and [created]. It owns what they depend on: whether the role
 * went out, and the block index to tool index map (ADR-GW-03-012). A null chunk means nothing to send.
 */
internal class StreamChunks(
    private val id: String,
    private val model: ModelName,
    private val created: Long,
) {
    private var firstSent = false
    private val tools = mutableMapOf<Int, ToolBlock>()

    fun text(text: String): ProviderStreamEvent? =
        if (text.isEmpty()) null else chunk(ChunkDelta(text, firstDelta(toolChunk = false)))

    fun toolStart(
        blockIndex: Int,
        toolId: String,
        name: String,
    ): ProviderStreamEvent {
        val tool = ToolBlock(tools.size)
        tools[blockIndex] = tool
        return toolChunk(
            JsonValue.Object(mapOf("index" to tool.number()) + ReplyMembers.whole(toolId, name, "").members),
        )
    }

    fun arguments(
        blockIndex: Int,
        json: String,
    ): ProviderStreamEvent? {
        val tool = tools[blockIndex]
        return if (tool == null || json.isEmpty()) null else argumentsChunk(tool, json)
    }

    /** `""` does not parse, and a tool with no input may send no delta at all (ADR-GW-03-012). */
    fun toolStop(blockIndex: Int): ProviderStreamEvent? =
        tools[blockIndex]?.takeUnless { it.argumentsSent }?.let { argumentsChunk(it, EMPTY_INPUT) }

    /** Held thinking rides here: a chunk of its own would be the first frame (ADR-GW-03-011). */
    fun finish(
        reason: FinishReason,
        thinking: List<JsonValue>,
    ): ProviderStreamEvent {
        val extension =
            if (thinking.isEmpty()) {
                emptyMap()
            } else {
                mapOf(ChatExtensions.MEMBER to ReplyMembers.thinkingExtension(thinking))
            }
        return chunk(ChunkDelta(extras = JsonValue.Object(firstDelta(toolChunk = false).members + extension)), reason)
    }

    fun usage(usage: Usage): ProviderStreamEvent =
        ProviderStreamEvent.Chunk(ChatChunk(id, created, model, emptyList(), usage))

    private fun argumentsChunk(
        tool: ToolBlock,
        json: String,
    ): ProviderStreamEvent {
        tool.argumentsSent = true
        val function = JsonValue.Object(mapOf("arguments" to JsonValue.Text(json)))
        return toolChunk(JsonValue.Object(mapOf("index" to tool.number(), "function" to function)))
    }

    private fun toolChunk(call: JsonValue): ProviderStreamEvent {
        val delta = firstDelta(toolChunk = true).members + ("tool_calls" to JsonValue.Array(listOf(call)))
        return chunk(ChunkDelta(extras = JsonValue.Object(delta)))
    }

    // The first chunk carries the role: there is no empty role chunk here (ADR-GW-03-007).
    private fun firstDelta(toolChunk: Boolean): JsonValue.Object {
        if (firstSent) return JsonValue.Object.EMPTY
        firstSent = true
        val role = mapOf("role" to JsonValue.Text("assistant"))
        return JsonValue.Object(if (toolChunk) role + ("content" to JsonValue.Null) else role)
    }

    private fun chunk(
        delta: ChunkDelta,
        finish: FinishReason? = null,
    ): ProviderStreamEvent {
        val choice = ChunkChoice(0, delta, finish, if (finish == null) OPEN_CHOICE else LAST_CHOICE)
        return ProviderStreamEvent.Chunk(ChatChunk(id, created, model, listOf(choice)))
    }

    private class ToolBlock(
        private val index: Int,
    ) {
        var argumentsSent = false

        fun number(): JsonValue = JsonValue.Number(index.toString())
    }

    private companion object {
        const val EMPTY_INPUT = "{}"
        val OPEN_CHOICE = JsonValue.Object(mapOf("logprobs" to JsonValue.Null, "finish_reason" to JsonValue.Null))
        val LAST_CHOICE = JsonValue.Object(mapOf("logprobs" to JsonValue.Null))
    }
}
