package io.github.pdroacordi.llmgateway.mockprovider.generation

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/** One element of an embeddings request's `input`. */
sealed interface EmbeddingInput {
    /** Approximate tokens for `usage` (ADR-FND-03-005). */
    val tokens: Int

    data class Text(
        val text: String,
    ) : EmbeddingInput {
        override val tokens: Int get() = Tokenizer.count(text)
    }

    data class Tokens(
        val ids: List<Int>,
    ) : EmbeddingInput {
        override val tokens: Int get() = ids.size
    }
}

/**
 * Feature hashing (Weinberger et al., 2009): each word adds +1 or -1 to one bucket, then the vector is L2-normalised.
 * Shared words give positive cosine similarity, disjoint texts are near-orthogonal; not semantic (ADR-FND-03-006).
 */
class Embedder {
    fun embed(
        input: EmbeddingInput,
        dimensions: Int,
    ): FloatArray {
        require(dimensions >= 1) { "dimensions must be at least 1" }
        val features =
            when (input) {
                is EmbeddingInput.Text -> {
                    val lower = input.text.lowercase()
                    WORD
                        .findAll(lower)
                        .map { it.value }
                        .toList()
                        .ifEmpty { listOf(lower) }
                }

                is EmbeddingInput.Tokens -> {
                    input.ids.map { "t$it" }
                }
            }
        val sums = DoubleArray(dimensions)
        features.forEach { feature ->
            val hash = Fnv1a.hash64(feature)
            val bucket = ((hash ushr 1) % dimensions).toInt()
            sums[bucket] += if (hash and 1L == 0L) 1.0 else -1.0
        }
        // Colliding features can cancel to zero; a fixed unit vector keeps the norm at 1.
        if (sums.all { it == 0.0 }) sums[0] = 1.0
        val norm = sqrt(sums.sumOf { it * it })
        return FloatArray(dimensions) { (sums[it] / norm).toFloat() }
    }

    companion object {
        private val WORD = Regex("""[\p{L}\p{N}]+""")

        /** OpenAI `encoding_format: base64`: the float32 values, little-endian, standard Base64. */
        fun base64(vector: FloatArray): String {
            val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            vector.forEach(buffer::putFloat)
            return Base64.getEncoder().encodeToString(buffer.array())
        }
    }
}
