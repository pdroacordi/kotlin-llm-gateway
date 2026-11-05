package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.providers.json.extrasExcept
import tools.jackson.databind.JsonNode

/**
 * The `usage` member of an embedding reply; null when absent or unreadable, which counts as omitted. Its own reader:
 * this reply has no `completion_tokens`, which [OpenAiUsageReader] requires (ADR-GW-05-007).
 */
internal object OpenAiEmbeddingUsageReader {
    private val USAGE_MEMBERS = setOf("prompt_tokens", "completion_tokens", "total_tokens")

    fun read(node: JsonNode?): Usage? {
        val input = node?.get("prompt_tokens")?.toTokenCount() ?: return null
        return Usage(input, TokenCount(0), extras = node.extrasExcept(USAGE_MEMBERS))
    }
}
