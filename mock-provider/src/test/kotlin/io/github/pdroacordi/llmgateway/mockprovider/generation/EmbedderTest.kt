package io.github.pdroacordi.llmgateway.mockprovider.generation

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

class EmbedderTest {
    private val embedder = Embedder()

    @Test
    fun `the same input gives the same vector`() {
        val text = EmbeddingInput.Text("How do I reset my password?")
        assertArrayEquals(embedder.embed(text, 1536), embedder.embed(text, 1536))
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 3, 1536])
    fun `vectors have the requested dimensions and norm 1`(dimensions: Int) {
        val vector = embedder.embed(EmbeddingInput.Text("the quick brown fox jumps"), dimensions)

        assertEquals(dimensions, vector.size)
        assertEquals(1.0, norm(vector), 1e-6)
    }

    @Test
    fun `identical inputs have cosine 1, shared words beat disjoint words`() {
        val a = embedder.embed(EmbeddingInput.Text("How do I reset my password"), 256)
        val sameWords = embedder.embed(EmbeddingInput.Text("how do I RESET my password"), 256)
        val reworded = embedder.embed(EmbeddingInput.Text("password reset: how do I do it"), 256)
        val disjoint = embedder.embed(EmbeddingInput.Text("weather tomorrow in lisbon"), 256)

        assertEquals(1.0, cosine(a, sameWords), 1e-6)
        assertTrue(
            cosine(a, reworded) > cosine(a, disjoint) + 0.3,
        ) { "${cosine(a, reworded)} vs ${cosine(a, disjoint)}" }
    }

    @Test
    fun `token arrays hash each id`() {
        val tokens = embedder.embed(EmbeddingInput.Tokens(listOf(1, 2, 3)), 64)

        assertEquals(1.0, norm(tokens), 1e-6)
        assertArrayEquals(tokens, embedder.embed(EmbeddingInput.Tokens(listOf(1, 2, 3)), 64))
        assertTrue(cosine(tokens, embedder.embed(EmbeddingInput.Tokens(listOf(4, 5, 6)), 64)) < 1.0)
    }

    @Test
    fun `punctuation-only input hashes the whole string`() {
        val vector = embedder.embed(EmbeddingInput.Text("?!"), 16)

        assertEquals(1.0, norm(vector), 1e-6)
    }

    @Test
    fun `base64 is little-endian float32 and round-trips`() {
        val vector = embedder.embed(EmbeddingInput.Text("round trip"), 8)
        val encoded = Embedder.base64(vector)
        val decoded =
            ByteBuffer.wrap(Base64.getDecoder().decode(encoded)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let {
                FloatArray(it.remaining()).also(it::get)
            }

        assertArrayEquals(vector, decoded)
        assertEquals(8 * 4, Base64.getDecoder().decode(encoded).size)
    }

    @Test
    fun `approximate tokens of an input`() {
        assertEquals(4, EmbeddingInput.Text("Hello, world!").tokens)
        assertEquals(3, EmbeddingInput.Tokens(listOf(9, 9, 9)).tokens)
    }

    private fun norm(vector: FloatArray): Double = sqrt(vector.sumOf { it.toDouble() * it })

    private fun cosine(
        a: FloatArray,
        b: FloatArray,
    ): Double =
        a.indices.sumOf { a[it].toDouble() * b[it] } / (norm(a) * norm(b)).let { if (abs(it) < 1e-12) 1.0 else it }
}
