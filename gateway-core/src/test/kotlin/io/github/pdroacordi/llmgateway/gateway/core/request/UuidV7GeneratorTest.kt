package io.github.pdroacordi.llmgateway.gateway.core.request

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class UuidV7GeneratorTest {
    private fun clockAt(millis: Long): Clock = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

    @Test
    fun `the version is 7, the variant 10 and the first 48 bits the clock's milliseconds`() {
        UuidV7Generator(clockAt(MILLIS), Random(1)).next().toLongs { msb, lsb ->
            assertEquals(7L, (msb ushr 12) and 0xF)
            assertEquals(2L, lsb ushr 62)
            assertEquals(MILLIS, msb ushr 16)
        }
    }

    @Test
    fun `two IDs in one millisecond differ`() {
        val generator = UuidV7Generator(clockAt(MILLIS), Random(1))

        assertNotEquals(generator.next(), generator.next())
    }

    @Test
    fun `an ID from a later millisecond sorts higher`() {
        val random = Random(1)

        val earlier = UuidV7Generator(clockAt(MILLIS), random).next().toString()
        val later = UuidV7Generator(clockAt(MILLIS + 1), random).next().toString()

        assertTrue(later > earlier) { "$later should sort after $earlier" }
    }

    @Test
    fun `a clock outside the 48-bit range is a bug`() {
        assertThrows<IllegalStateException> { UuidV7Generator(clockAt(-1), Random(1)).next() }
    }

    private companion object {
        const val MILLIS = 1_689_000_000_000L
    }
}
