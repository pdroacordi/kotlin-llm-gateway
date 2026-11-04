package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.AssistantMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader

/** Bytes of a 2xx to a completion, or null when they are not one; the caller answers `UpstreamError`. */
internal class OpenAiResponseReader(
    private val trees: ObjectReader,
) {
    fun read(body: ByteArray): ProviderResult.Completed? =
        trees.readTreeOrNull(body)?.let { tree ->
            completion(tree)?.let { ProviderResult.Completed(it, usage(tree.get("usage"))) }
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
            else -> ChatCompletion(id, created, ModelName(model), choices, tree.extras(COMPLETION_MEMBERS))
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
                    message = AssistantMessage(content?.stringValue(null), message.extras(MESSAGE_MEMBERS)),
                    finishReason = node.get("finish_reason")?.stringValue(null)?.let(::FinishReason),
                    extras = node.extras(CHOICE_MEMBERS),
                )
            }
        }
    }

    private fun usage(node: JsonNode?): Usage? {
        val input = node?.get("prompt_tokens")?.toTokenCount()
        val output = node?.get("completion_tokens")?.toTokenCount()
        val cached = node?.get("prompt_tokens_details")?.get("cached_tokens")?.toTokenCount()
        return when {
            node == null || input == null || output == null -> null

            // The total would not fit, so the counts cannot be real: a bad count, like the rows above.
            input.value > Long.MAX_VALUE - output.value -> null

            else -> Usage(input, output, cached, extras = node.extras(USAGE_MEMBERS))
        }
    }

    private fun JsonNode.toTokenCount(): TokenCount? =
        takeIf { it.isCount() && it.longValue() >= 0 }?.let { TokenCount(it.longValue()) }

    private fun JsonNode.isCount(): Boolean = isIntegralNumber && canConvertToLong()

    private fun JsonNode.extras(typed: Set<String>): JsonValue.Object =
        JsonValue.Object(
            properties()
                .filterNot { (name, _) -> name in typed }
                .associate { (name, value) -> name to value.toJsonValue() },
        )

    private companion object {
        val COMPLETION_MEMBERS = setOf("id", "object", "created", "model", "choices", "usage")
        val CHOICE_MEMBERS = setOf("index", "message", "finish_reason")
        val MESSAGE_MEMBERS = setOf("role", "content")
        val USAGE_MEMBERS = setOf("prompt_tokens", "completion_tokens", "total_tokens")
    }
}
