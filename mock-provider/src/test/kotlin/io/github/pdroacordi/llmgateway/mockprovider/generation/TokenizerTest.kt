package io.github.pdroacordi.llmgateway.mockprovider.generation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.SplittableRandom

class TokenizerTest {
    @ParameterizedTest(name = "{0} has {1} tokens")
    @CsvSource(
        delimiter = '|',
        value = [
            "Hello world|2",
            "Hello, world!|4",
            "abc123 456|2",
            "...|3",
            "café naïve|2",
            "日本語 テキスト|2",
            "hi 👋|2",
            "a-b|3",
        ],
    )
    fun `counts letter and digit runs and single symbols`(
        text: String,
        expected: Int,
    ) {
        assertEquals(expected, Tokenizer.count(text))
    }

    @Test
    fun `an emoji with a skin-tone modifier is two symbols`() {
        assertEquals(2, Tokenizer.count("👋🏽"))
    }

    @Test
    fun `whitespace-only text has no tokens and no pieces`() {
        assertEquals(0, Tokenizer.count("  \n\t "))
        assertEquals(emptyList<String>(), Tokenizer.pieces("  \n\t "))
        assertEquals(emptyList<String>(), Tokenizer.pieces(""))
    }

    @Test
    fun `each piece is one token with the whitespace before it`() {
        assertEquals(listOf("Hi", " there"), Tokenizer.pieces("Hi there"))
        assertEquals(listOf(" Hi", ",", "  there", "!\n"), Tokenizer.pieces(" Hi,  there!\n"))
    }

    @Test
    fun `the pieces of 200 random strings concatenate to the input and match the count`() {
        val random = SplittableRandom(7)
        val alphabet = "ab Zé9 ,.!\n\t日👋-"
        val size = alphabet.codePointCount(0, alphabet.length)
        repeat(200) {
            val length = 1 + random.nextInt(40)
            // Starts with a letter so no string is whitespace-only (that case has zero pieces, tested above).
            val text =
                "x" +
                    buildString {
                        repeat(length) {
                            val codePoint = alphabet.codePointAt(alphabet.offsetByCodePoints(0, random.nextInt(size)))
                            appendCodePoint(codePoint)
                        }
                    }
            val pieces = Tokenizer.pieces(text)
            assertEquals(text, pieces.joinToString(""))
            assertEquals(Tokenizer.count(text), pieces.size) { text }
        }
    }
}
