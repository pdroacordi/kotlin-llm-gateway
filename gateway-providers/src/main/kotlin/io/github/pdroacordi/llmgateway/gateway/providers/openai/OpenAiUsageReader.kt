package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.providers.json.extrasExcept
import tools.jackson.databind.JsonNode

/** The `usage` member of a completion or a chunk; null when absent or unreadable, which counts as omitted. */
internal object OpenAiUsageReader {
    private val USAGE_MEMBERS = setOf("prompt_tokens", "completion_tokens", "total_tokens")

    fun read(node: JsonNode?): Usage? {
        val input = node?.get("prompt_tokens")?.toTokenCount()
        val output = node?.get("completion_tokens")?.toTokenCount()
        val cached = node?.get("prompt_tokens_details")?.get("cached_tokens")?.toTokenCount()
        return when {
            node == null || input == null || output == null -> null

            // The total would not fit, so the counts cannot be real: a bad count, like the rows above.
            input.value > Long.MAX_VALUE - output.value -> null

            else -> Usage(input, output, cached, extras = node.extrasExcept(USAGE_MEMBERS))
        }
    }

    private fun JsonNode.toTokenCount(): TokenCount? =
        if (isIntegralNumber && canConvertToLong() && longValue() >= 0) TokenCount(longValue()) else null
}
