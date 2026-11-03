package io.github.pdroacordi.llmgateway.gateway.core.request

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

// RFC 9562 section 5.7 layout: 48-bit ms timestamp, version 7, 12 random bits, variant 0b10, 62 random bits.
class RequestIdGeneratorTest {
    @Test
    fun `the version nibble is 7 and the variant bits are 10`() {
        val id = generator(MILLIS).next().value

        id.toLongs { msb, lsb ->
            assertEquals(7L, (msb ushr 12) and 0xF)
            assertEquals(2L, lsb ushr 62)
        }
    }

    @Test
    fun `the first 48 bits are the clock's milliseconds`() {
        val id = generator(MILLIS).next().value

        id.toLongs { msb, _ -> assertEquals(MILLIS, msb ushr 16) }
    }

    @Test
    fun `the text form is canonical lowercase hex and dash`() {
        val text = generator(MILLIS).next().toString()

        assertTrue(CANONICAL_V7.matches(text)) { text }
        assertTrue(text.startsWith("0189")) { text }
    }

    @Test
    fun `an ID from a later millisecond sorts higher`() {
        val random = Random(SEED)
        val earlier = RequestIdGenerator(clockAt(MILLIS), random).next().toString()
        val later = RequestIdGenerator(clockAt(MILLIS + 1), random).next().toString()

        assertTrue(later > earlier) { "$later should sort after $earlier" }
    }

    @Test
    fun `the random bits come from the injected Random`() {
        val first = RequestIdGenerator(clockAt(MILLIS), Random(1)).next()
        val second = RequestIdGenerator(clockAt(MILLIS), Random(2)).next()
        val sameSeed = RequestIdGenerator(clockAt(MILLIS), Random(1)).next()

        assertNotEquals(first, second)
        assertEquals(first, sameSeed)
    }

    @Test
    fun `consecutive IDs in one millisecond differ`() {
        val generator = generator(MILLIS)

        assertNotEquals(generator.next(), generator.next())
    }

    @Test
    fun `the largest 48-bit timestamp is accepted`() {
        val id = generator(MAX_MILLIS).next().value

        id.toLongs { msb, _ -> assertEquals(MAX_MILLIS, msb ushr 16) }
    }

    @ParameterizedTest
    @ValueSource(longs = [-1L, 1L shl 48])
    fun `a clock outside the 48-bit range is a bug`(millis: Long) {
        assertThrows<IllegalStateException> { generator(millis).next() }
    }

    private fun generator(millis: Long) = RequestIdGenerator(clockAt(millis), Random(SEED))

    private fun clockAt(millis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    private companion object {
        const val MILLIS = 1_688_208_000_000L
        const val MAX_MILLIS = (1L shl 48) - 1
        const val SEED = 42
        val CANONICAL_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
