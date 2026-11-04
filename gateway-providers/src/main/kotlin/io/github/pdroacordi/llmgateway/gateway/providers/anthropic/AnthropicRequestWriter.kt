package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.util.HexFormat

internal sealed interface WrittenRequest {
    data class Body(
        val tree: ObjectNode,
    ) : WrittenRequest {
        override fun toString(): String = "WrittenRequest.Body"
    }

    data class Refused(
        val failure: ProviderFailure.UnsupportedByProvider,
    ) : WrittenRequest
}

/** [ChatCall] to a Messages body, or a refusal before any I/O (GW-03 section 4). Reads extras (ADR-GW-03-017). */
internal class AnthropicRequestWriter(
    private val nodes: JsonNodeFactory,
    private val trees: ObjectReader,
    private val defaultMaxTokens: Int,
) {
    fun write(call: ChatCall): WrittenRequest = written(call, stream = false)

    fun writeStream(call: ChatCall): WrittenRequest = written(call, stream = true)

    private fun written(
        call: ChatCall,
        stream: Boolean,
    ): WrittenRequest {
        val extras = call.request.extras
        AnthropicMemberPolicy.refusal(extras)?.let { return WrittenRequest.Refused(it) }
        val refusals = Refusals()
        val tree = body(call, refusals, stream)
        return refusals.first?.let(WrittenRequest::Refused) ?: WrittenRequest.Body(tree)
    }

    private fun body(
        call: ChatCall,
        refusals: Refusals,
        stream: Boolean,
    ): ObjectNode {
        val extras = call.request.extras.present()
        val body = nodes.objectNode()
        body.put("model", call.deployment.model.value)
        val cap = extras["max_completion_tokens"] ?: extras["max_tokens"]
        if (cap == null) body.put("max_tokens", defaultMaxTokens) else body.set("max_tokens", cap.toJsonNode(nodes))
        val conversation = AnthropicConversationWriter(nodes, trees, refusals).write(call.request.messages)
        conversation.system?.let { body.set("system", it) }
        body.set("messages", conversation.messages)
        AnthropicToolWriter(nodes, refusals).write(extras, body)
        stopSequences(extras["stop"], refusals)?.let { body.set("stop_sequences", it.toJsonNode(nodes)) }
        FORWARDED.forEach { name -> extras[name]?.let { body.set(name, it.toJsonNode(nodes)) } }
        userId(extras, refusals)?.let { body.putObject("metadata").put("user_id", it) }
        if (stream) body.put("stream", true)
        return body
    }

    private fun stopSequences(
        stop: JsonValue?,
        refusals: Refusals,
    ): JsonValue? =
        when {
            stop == null -> {
                null
            }

            stop is JsonValue.Text -> {
                JsonValue.Array(listOf(stop))
            }

            stop is JsonValue.Array && stop.items.all { it is JsonValue.Text } -> {
                stop
            }

            else -> {
                refusals.unsupportedValue("stop")
                null
            }
        }

    // ADR-GW-03-014: the provider asks for an opaque identifier, and nothing stops a client sending an email address.
    private fun userId(
        extras: Map<String, JsonValue>,
        refusals: Refusals,
    ): String? {
        val member = USER_MEMBERS.firstOrNull { it in extras } ?: return null
        val value = extras[member] as? JsonValue.Text
        if (value == null) refusals.unsupportedValue(member)
        return value?.let { sha256(it.value) }
    }

    private fun sha256(value: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST).digest(value.encodeToByteArray()))

    companion object {
        /** Named here so `warmUp()` loads the same algorithm the first hash asks for. */
        const val DIGEST = "SHA-256"
        private val FORWARDED = listOf("temperature", "top_p", "top_k", "cache_control")
        private val USER_MEMBERS = listOf("safety_identifier", "user")
    }
}

/** Members set to `null` count as absent (GW-03 section 4). */
internal fun JsonValue.Object.present(): Map<String, JsonValue> = members.filterValues { it != JsonValue.Null }
