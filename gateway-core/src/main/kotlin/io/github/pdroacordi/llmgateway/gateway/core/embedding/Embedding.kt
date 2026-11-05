package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

/** [extras]: `dimensions`, `encoding_format`, `user`, unknown members, verbatim (ADR-GW-05-001). */
data class EmbeddingRequest(
    val model: ModelName,
    val input: EmbeddingInput,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        requireNoTypedMember(extras, REQUEST_MEMBERS)
    }

    internal companion object {
        val REQUEST_MEMBERS = setOf("model", "input")
    }
}

/** One JSON value as UTF-8, never parsed: the gateway carries a vector, it does not read it (ADR-GW-05-005). */
class RawJson(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is RawJson && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "RawJson(size=${bytes.size})"
}

/** [vector] is an array of numbers or a base64 string, as the provider wrote it (ADR-GW-05-004). */
data class Embedding(
    val index: Int,
    val vector: RawJson,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        requireNoTypedMember(extras, ITEM_MEMBERS)
    }

    private companion object {
        val ITEM_MEMBERS = setOf("object", "index", "embedding")
    }
}

data class EmbeddingList(
    val model: ModelName,
    val data: List<Embedding>,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        requireNoTypedMember(extras, LIST_MEMBERS)
    }

    private companion object {
        val LIST_MEMBERS = setOf("object", "data", "model", "usage", ChatExtensions.MEMBER)
    }
}

internal fun requireNoTypedMember(
    extras: JsonValue.Object,
    typed: Set<String>,
) {
    require(extras.members.keys.none { it in typed }) { "extras holds a typed member." }
}
