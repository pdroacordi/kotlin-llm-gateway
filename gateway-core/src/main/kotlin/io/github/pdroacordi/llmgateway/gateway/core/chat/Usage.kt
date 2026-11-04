package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

@JvmInline
value class TokenCount(
    val value: Long,
) {
    init {
        require(value >= 0) { "A token count cannot be negative." }
    }

    operator fun plus(other: TokenCount): TokenCount = TokenCount(Math.addExact(value, other.value))
}

/**
 * [cachedInputTokens] is a subset of [inputTokens]; [extras] holds provider detail objects in the OpenAI shape.
 * [estimated] is true when the provider omitted usage (ADR-GW-01-011).
 */
data class Usage(
    val inputTokens: TokenCount,
    val outputTokens: TokenCount,
    val cachedInputTokens: TokenCount? = null,
    val estimated: Boolean = false,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    val totalTokens: TokenCount get() = inputTokens + outputTokens

    override fun toString(): String =
        "Usage(input=${inputTokens.value}, output=${outputTokens.value}, estimated=$estimated)"
}
