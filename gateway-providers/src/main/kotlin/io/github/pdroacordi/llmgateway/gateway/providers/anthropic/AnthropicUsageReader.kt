package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import tools.jackson.databind.JsonNode

/** The counts of Anthropic's `usage` so far, in tokens; on a stream the last value of each wins (ADR-GW-03-008). */
internal data class UsageCounts(
    val input: Long? = null,
    val output: Long? = null,
    val cacheRead: Long? = null,
    val cacheCreation: Long? = null,
    val thinking: Long? = null,
    val unreadable: Set<String> = emptySet(),
) {
    /** `message_start`: its `output_tokens` is a placeholder, so output comes only from `message_delta`. */
    fun startedBy(node: JsonNode?): UsageCounts =
        updatedBy(node).let { it.copy(output = null, thinking = null, unreadable = it.unreadable - OUTPUT_TOKENS) }

    /** A count absent or null in [node] keeps its value; an unreadable one omits usage until a readable one follows. */
    fun updatedBy(node: JsonNode?): UsageCounts =
        if (node == null || !node.isObject) {
            this
        } else {
            UsageCounts(
                input = node.count(INPUT_TOKENS) ?: input,
                output = node.count(OUTPUT_TOKENS) ?: output,
                cacheRead = node.count(CACHE_READ_TOKENS) ?: cacheRead,
                cacheCreation = node.count(CACHE_CREATION_TOKENS) ?: cacheCreation,
                thinking = node.get("output_tokens_details")?.count("thinking_tokens") ?: thinking,
                unreadable = unreadableAfter(node),
            )
        }

    private fun unreadableAfter(node: JsonNode): Set<String> {
        val sent = REQUIRED_READABLE.filter { node.get(it)?.isNull == false }
        return unreadable - sent.toSet() + sent.filter { node.count(it) == null }
    }

    private fun JsonNode.count(name: String): Long? =
        get(name)?.takeIf { it.isIntegralNumber && it.canConvertToLong() && it.longValue() >= 0 }?.longValue()

    private companion object {
        const val INPUT_TOKENS = "input_tokens"
        const val OUTPUT_TOKENS = "output_tokens"
        const val CACHE_READ_TOKENS = "cache_read_input_tokens"
        const val CACHE_CREATION_TOKENS = "cache_creation_input_tokens"
        val REQUIRED_READABLE = listOf(INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ_TOKENS, CACHE_CREATION_TOKENS)
    }
}

/** `usage` to the gateway's meaning of it (GW-03 section 4, Usage); null counts as omitted, so it is estimated. */
internal object AnthropicUsageReader {
    fun read(node: JsonNode?): Usage? = usage(UsageCounts().updatedBy(node))

    fun usage(counts: UsageCounts): Usage? {
        val read = counts.cacheRead ?: 0
        val creation = counts.cacheCreation ?: 0
        val input = counts.input?.let { sum(it, read, creation) }
        val output = counts.output
        return when {
            input == null || output == null || counts.unreadable.isNotEmpty() -> {
                null
            }

            // The total would not fit, so the counts cannot be real.
            sum(input, output) == null -> {
                null
            }

            else -> {
                Usage(TokenCount(input), TokenCount(output), TokenCount(read), extras = extras(counts, read, creation))
            }
        }
    }

    // The OpenAI shape, as Usage's KDoc says, so ChatResponseWriter writes each member once.
    private fun extras(
        counts: UsageCounts,
        read: Long,
        creation: Long,
    ): JsonValue.Object =
        JsonValue.Object(
            mapOf(
                "prompt_tokens_details" to detail("cached_tokens", read),
                "completion_tokens_details" to detail("reasoning_tokens", counts.thinking ?: 0),
                "cache_creation_input_tokens" to JsonValue.Number(creation.toString()),
            ),
        )

    private fun detail(
        name: String,
        count: Long,
    ): JsonValue.Object = JsonValue.Object(mapOf(name to JsonValue.Number(count.toString())))

    private fun sum(vararg counts: Long): Long? =
        counts.fold(0L as Long?) { total, count ->
            total?.takeIf { it <= Long.MAX_VALUE - count }?.plus(count)
        }
}
