package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

/** `stop`, `length`, `tool_calls`, `content_filter`, ... */
@JvmInline
value class FinishReason(
    val value: String,
)

/** [created] is in epoch seconds; [extras]: `service_tier`, `system_fingerprint`, ... */
data class ChatCompletion(
    val id: String,
    val created: Long,
    val model: ModelName,
    val choices: List<ChatChoice>,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChatCompletion(model=${model.value}, choices=${choices.size})"
}

/** [extras]: `logprobs`, ... */
data class ChatChoice(
    val index: Int,
    val message: AssistantMessage,
    val finishReason: FinishReason?,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "ChatChoice(index=$index)"
}

/** [extras]: `refusal`, `annotations`, `tool_calls`, `audio`, ... */
data class AssistantMessage(
    val content: String?,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    override fun toString(): String = "AssistantMessage(length=${content?.length})"
}
