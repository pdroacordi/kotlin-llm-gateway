package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue

/** [defaultDimensions] prices a request with no `dimensions`; [maxResponseBytes] is the most a reply can be. */
data class EmbeddingReplyLimits(
    val defaultDimensions: Int,
    val maxResponseBytes: Int,
)

// ADR-GW-05-006's table: base64 is 16/3 bytes a value, an indented float 33.
private const val BASE64_NUMERATOR = 16L
private const val BASE64_DENOMINATOR = 3L
private const val FLOAT_BYTES = 33L
private const val BASE64 = "base64"

/**
 * The reply [request] is priced at: items x dimensions x bytes a value, rounded up and capped at
 * [EmbeddingReplyLimits.maxResponseBytes] (ADR-GW-05-011). An estimate: per-item JSON and base64 padding are unpriced,
 * under 1 KiB an item. A value the provider will refuse prices as the widest.
 */
fun worstCaseReplyBytes(
    request: EmbeddingRequest,
    limits: EmbeddingReplyLimits,
): Long {
    val cap = limits.maxResponseBytes.toLong()
    val items = request.input.items().toLong()
    val dimensions = request.dimensions() ?: limits.defaultDimensions.toLong()
    return when {
        items == 0L -> 0

        // A value takes over a byte, so past the cap in values is past it in bytes; dimensions can near Long's top.
        dimensions > cap / items -> cap

        else -> minOf(bytes(items * dimensions, request.isBase64()), cap)
    }
}

private fun bytes(
    values: Long,
    base64: Boolean,
): Long =
    if (base64) {
        (values * BASE64_NUMERATOR + BASE64_DENOMINATOR - 1) / BASE64_DENOMINATOR
    } else {
        values * FLOAT_BYTES
    }

private fun EmbeddingInput.items(): Int =
    when (this) {
        is EmbeddingInput.Text -> 1
        is EmbeddingInput.Texts -> texts.size
        is EmbeddingInput.TokenIds -> 1
        is EmbeddingInput.TokenIdLists -> lists.size
    }

private fun EmbeddingRequest.dimensions(): Long? =
    (extras.members["dimensions"] as? JsonValue.Number)?.literal?.toLongOrNull()?.takeIf { it > 0 }

private fun EmbeddingRequest.isBase64(): Boolean =
    (extras.members["encoding_format"] as? JsonValue.Text)?.value == BASE64
