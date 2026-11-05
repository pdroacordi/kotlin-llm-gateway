package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorCodes
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import java.math.BigDecimal

internal sealed interface MemberRule {
    /** The writer owns it. */
    data object Translated : MemberRule

    data object Forward : MemberRule

    data object Ignore : MemberRule

    /** A value in [neutral] changes nothing, so it counts as absent; any other is refused with [code]. */
    data class IgnoreWhen(
        val neutral: List<JsonValue>,
        val code: String,
    ) : MemberRule

    data class Reject(
        val code: String,
    ) : MemberRule
}

/** One rule per top-level member of the OpenAI request (GW-03 section 4, ADR-GW-03-005); the unlisted are refused. */
internal object AnthropicMemberPolicy {
    private val ZERO = JsonValue.Number("0")
    private val REJECTED = MemberRule.Reject(ErrorCodes.UNSUPPORTED_PARAMETER)

    private fun neutral(vararg values: JsonValue): MemberRule =
        MemberRule.IgnoreWhen(values.toList(), ErrorCodes.UNSUPPORTED_VALUE)

    val rules: Map<String, MemberRule> =
        buildMap {
            listOf(
                "model",
                "messages",
                "stream",
                "max_completion_tokens",
                "max_tokens",
                "stop",
                "tools",
                "tool_choice",
                "parallel_tool_calls",
                "safety_identifier",
                "user",
                "response_format",
            ).forEach { put(it, MemberRule.Translated) }
            listOf("temperature", "top_p", "top_k", "cache_control").forEach { put(it, MemberRule.Forward) }
            listOf(
                "stream_options",
                "metadata",
                "store",
                "prompt_cache_key",
                "prompt_cache_options",
                "prompt_cache_retention",
            ).forEach { put(it, MemberRule.Ignore) }
            put("service_tier", neutral(JsonValue.Text("auto"), JsonValue.Text("default")))
            put("n", neutral(JsonValue.Number("1")))
            put("logprobs", neutral(JsonValue.Bool(false)))
            put("frequency_penalty", neutral(ZERO))
            put("presence_penalty", neutral(ZERO))
            put("modalities", neutral(JsonValue.Array(listOf(JsonValue.Text("text")))))
            listOf(
                "top_logprobs",
                "seed",
                "logit_bias",
                "audio",
                "prediction",
                "moderation",
                "reasoning_effort",
                "verbosity",
                "web_search_options",
                "functions",
                "function_call",
            ).forEach { put(it, REJECTED) }
        }

    fun ruleOf(member: String): MemberRule = rules[member] ?: REJECTED

    /** The first member of [extras] that cannot be honoured; `null` counts as absent. */
    fun refusal(extras: JsonValue.Object): ProviderFailure.UnsupportedByProvider? =
        extras.members.firstNotNullOfOrNull { (name, value) ->
            if (value == JsonValue.Null) null else refusal(name, value, ruleOf(name))
        }

    private fun refusal(
        name: String,
        value: JsonValue,
        rule: MemberRule,
    ): ProviderFailure.UnsupportedByProvider? {
        val code =
            when (rule) {
                is MemberRule.Reject -> rule.code
                is MemberRule.IgnoreWhen -> rule.code.takeUnless { rule.neutral.any { item -> item.sameAs(value) } }
                else -> null
            }
        return code?.let { ProviderFailure.UnsupportedByProvider(it, ProviderErrorFields.safeParam(name)) }
    }

    // Numeric, so `0.0` is the default `0`.
    private fun JsonValue.sameAs(other: JsonValue): Boolean =
        if (this is JsonValue.Number && other is JsonValue.Number) {
            BigDecimal(literal).compareTo(BigDecimal(other.literal)) == 0
        } else {
            this == other
        }
}
