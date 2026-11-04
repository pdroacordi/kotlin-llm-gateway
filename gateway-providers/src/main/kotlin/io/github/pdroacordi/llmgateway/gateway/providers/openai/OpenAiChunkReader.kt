package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkDelta
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.providers.json.extrasExcept
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader

internal sealed interface ChunkRead {
    data class Read(
        val chunk: ChatChunk,
    ) : ChunkRead

    /** The provider's `message` is never read (ADR-GW-01-005). */
    data class InBandError(
        val fields: ProviderErrorFields,
    ) : ChunkRead

    data object Unreadable : ChunkRead
}

/**
 * The `data` bytes of one event to a [ChunkRead]. A typed field takes only a value of its type, and anything else stays
 * in `extras`, so `{}` and `{"content":null}` come out as they came in (ADR-GW-02-002).
 */
internal class OpenAiChunkReader(
    private val trees: ObjectReader,
) {
    fun read(data: ByteArray): ChunkRead {
        val tree = trees.readTreeOrNull(data)?.takeIf { it.isObject }
        val error = tree?.get("error")?.takeUnless { it.isNull }
        return when {
            tree == null -> ChunkRead.Unreadable
            error != null -> ChunkRead.InBandError(ProviderErrorFields.of(error))
            else -> chunk(tree)?.let(ChunkRead::Read) ?: ChunkRead.Unreadable
        }
    }

    private fun chunk(tree: JsonNode): ChatChunk? {
        val id = tree.get("id")?.takeIf { it.isString }?.stringValue()
        val created = tree.get("created")?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()
        val model =
            tree
                .get("model")
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        val choices = choices(tree.get("choices"))
        return when {
            id == null || created == null -> {
                null
            }

            model == null || choices == null -> {
                null
            }

            else -> {
                val usage = OpenAiUsageReader.read(tree.get("usage"))
                ChatChunk(id, created, ModelName(model), choices, usage, tree.extrasExcept(CHUNK_MEMBERS))
            }
        }
    }

    private fun choices(node: JsonNode?): List<ChunkChoice>? {
        if (node == null || !node.isArray) return null
        return node.values().mapNotNull(::choice).takeIf { it.size == node.size() }
    }

    private fun choice(node: JsonNode): ChunkChoice? {
        val delta = node.get("delta")?.takeIf { it.isObject } ?: return null
        val finishReason = node.get("finish_reason")?.takeIf { it.isString }
        val typed = if (finishReason == null) CHOICE_MEMBERS else CHOICE_MEMBERS + "finish_reason"
        return ChunkChoice(
            index = node.get("index")?.takeIf { it.canConvertToInt() }?.intValue() ?: 0,
            delta = delta(delta),
            finishReason = finishReason?.stringValue()?.let(::FinishReason),
            extras = node.extrasExcept(typed),
        )
    }

    private fun delta(node: JsonNode): ChunkDelta {
        val content = node.get("content")?.takeIf { it.isString }
        val typed = if (content == null) emptySet() else DELTA_MEMBERS
        return ChunkDelta(content?.stringValue(), node.extrasExcept(typed))
    }

    private companion object {
        val CHUNK_MEMBERS = setOf("id", "object", "created", "model", "choices", "usage")
        val CHOICE_MEMBERS = setOf("index", "delta")
        val DELTA_MEMBERS = setOf("content")
    }
}
