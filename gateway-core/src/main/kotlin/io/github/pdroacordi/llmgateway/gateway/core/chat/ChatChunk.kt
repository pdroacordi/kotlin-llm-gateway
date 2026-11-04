package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

/**
 * One streamed delta; [usage] is set on the chunk that reports it, and no `toString` in this file prints text. A typed
 * field holds only a value of its type, the rest stays in `extras`, so presence and `null` survive (ADR-GW-02-002).
 */
data class ChatChunk(
    val id: String,
    // Epoch seconds.
    val created: Long,
    val model: ModelName,
    val choices: List<ChunkChoice>,
    val usage: Usage? = null,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChatChunk(model=${model.value}, choices=${choices.size}, usage=${usage != null})"
}

/** [finishReason] is null when absent or null, then kept in [extras] with `logprobs`, ... */
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    val finishReason: FinishReason? = null,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChunkChoice(index=$index)"
}

/** [content] is null when absent or null, then kept in [extras] with `role`, `tool_calls`, `refusal`, ... */
data class ChunkDelta(
    val content: String? = null,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChunkDelta(length=${content?.length})"
}

/** What the client sent in `stream_options`; `include_usage` is always true upstream (ADR-GW-02-005). */
data class StreamOptions(
    val includeUsage: Boolean,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "StreamOptions(includeUsage=$includeUsage, extras=${extras.members.size})"
}
