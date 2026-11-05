package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.JsonMembers
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import tools.jackson.databind.ObjectReader

/**
 * Bytes of a 2xx to an embedding list, or null when they are not one; the caller answers `UpstreamError`. A vector is
 * copied unread (GW-05 section 5, "The reply scan"); small members go through the tree read, on their slice.
 */
internal class OpenAiEmbeddingReader(
    private val trees: ObjectReader,
) {
    fun read(body: ByteArray): EmbeddingResult.Embedded? = JsonMembers.scan(trees, body, ::reply)

    private fun reply(scan: JsonMembers): EmbeddingResult.Embedded {
        var data: List<Embedding>? = null
        var model: String? = null
        var usage: Usage? = null
        val extras = linkedMapOf<String, JsonValue>()
        scan.forEachMember { name ->
            when (name) {
                "data" -> data = items(scan)
                "model" -> model = scan.text()
                "usage" -> usage = OpenAiEmbeddingUsageReader.read(trees.readTreeOrNull(scan.raw()))
                "object", ChatExtensions.MEMBER -> scan.skip()
                else -> extras[name] = extra(scan)
            }
        }
        val items = data ?: scan.unreadable()
        val name = model?.takeIf { it.isNotBlank() } ?: scan.unreadable()
        return EmbeddingResult.Embedded(EmbeddingList(ModelName(name), items, JsonValue.Object(extras)), usage)
    }

    private fun items(scan: JsonMembers): List<Embedding> {
        val items = mutableListOf<Embedding>()
        scan.forEachItem { items += item(scan) }
        if (items.isEmpty()) scan.unreadable()
        return items
    }

    private fun item(scan: JsonMembers): Embedding {
        var index: Int? = null
        var vector: RawJson? = null
        val extras = linkedMapOf<String, JsonValue>()
        scan.forEachMember { name ->
            when (name) {
                "embedding" -> vector = vector(scan)

                // Without it a vector cannot be matched to its input, and a guess corrupts a search silently.
                "index" -> index = scan.int() ?: scan.unreadable()

                "object" -> scan.skip()

                else -> extras[name] = extra(scan)
            }
        }
        return Embedding(index ?: scan.unreadable(), vector ?: scan.unreadable(), JsonValue.Object(extras))
    }

    private fun vector(scan: JsonMembers): RawJson =
        if (scan.atArray || scan.atString) RawJson(scan.raw()) else scan.unreadable()

    private fun extra(scan: JsonMembers): JsonValue =
        trees.readTreeOrNull(scan.raw())?.toJsonValue() ?: scan.unreadable()
}
