package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.AssistantMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.providers.json.extrasExcept
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader

/** Bytes of a 2xx to a completion, or null when they are not one; the caller answers `UpstreamError`. */
internal class OpenAiResponseReader(
    private val trees: ObjectReader,
) {
    fun read(body: ByteArray): ProviderResult.Completed? =
        trees.readTreeOrNull(body)?.let { tree ->
            completion(tree)?.let { ProviderResult.Completed(it, OpenAiUsageReader.read(tree.get("usage"))) }
        }

    private fun completion(tree: JsonNode): ChatCompletion? {
        val id = tree.get("id")?.takeIf { it.isString }?.stringValue()
        val created = tree.get("created")?.takeIf { it.isCount() }?.longValue()
        val model =
            tree
                .get("model")
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        val choices = choices(tree.get("choices"))
        return when {
            id == null || created == null -> null
            model == null || choices == null -> null
            else -> ChatCompletion(id, created, ModelName(model), choices, tree.extrasExcept(COMPLETION_MEMBERS))
        }
    }

    private fun choices(node: JsonNode?): List<ChatChoice>? {
        if (node == null || !node.isArray || node.isEmpty) return null
        return node.values().mapNotNull(::choice).takeIf { it.size == node.size() }
    }

    private fun choice(node: JsonNode): ChatChoice? {
        val message = node.get("message")?.takeIf { it.isObject }
        val content = message?.get("content")
        return when {
            message == null -> {
                null
            }

            content != null && !content.isNull && !content.isString -> {
                null
            }

            else -> {
                ChatChoice(
                    index = node.get("index")?.takeIf { it.canConvertToInt() }?.intValue() ?: 0,
                    message = AssistantMessage(content?.stringValue(null), message.extrasExcept(MESSAGE_MEMBERS)),
                    finishReason = node.get("finish_reason")?.stringValue(null)?.let(::FinishReason),
                    extras = node.extrasExcept(CHOICE_MEMBERS),
                )
            }
        }
    }

    private fun JsonNode.isCount(): Boolean = isIntegralNumber && canConvertToLong()

    private companion object {
        val COMPLETION_MEMBERS = setOf("id", "object", "created", "model", "choices", "usage")
        val CHOICE_MEMBERS = setOf("index", "message", "finish_reason")
        val MESSAGE_MEMBERS = setOf("role", "content")
    }
}
