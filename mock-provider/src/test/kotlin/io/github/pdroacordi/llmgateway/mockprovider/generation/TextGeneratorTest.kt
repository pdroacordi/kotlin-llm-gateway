package io.github.pdroacordi.llmgateway.mockprovider.generation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextGeneratorTest {
    private val generator = TextGenerator()

    @Test
    fun `the same seed gives the same text`() {
        assertEquals(generator.lorem(42, 16), generator.lorem(42, 16))
    }

    @Test
    fun `a different seed gives a different text`() {
        assertNotEquals(generator.lorem(42, 16), generator.lorem(43, 16))
    }

    @Test
    fun `the text has the requested number of tokens, all from the vocabulary`() {
        val text = generator.lorem(7, 37)
        assertEquals(37, Tokenizer.count(text))
        val words = text.split(' ')
        assertEquals(37, words.size)
        assertTrue(words.all { it in TextGenerator.VOCABULARY }) { text }
    }

    @Test
    fun `the vocabulary has 64 distinct single-token lowercase words`() {
        assertEquals(64, TextGenerator.VOCABULARY.toSet().size)
        assertTrue(TextGenerator.VOCABULARY.all { Tokenizer.count(it) == 1 && it == it.lowercase() })
    }
}
