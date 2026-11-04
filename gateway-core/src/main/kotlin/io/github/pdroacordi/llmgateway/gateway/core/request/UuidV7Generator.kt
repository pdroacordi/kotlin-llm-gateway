package io.github.pdroacordi.llmgateway.gateway.core.request

import java.time.Clock
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * Builds UUIDv7s (RFC 9562 section 5.7) from [clock] and [random]. No counter within one millisecond: RFC 9562
 * section 6.2 makes it optional and nothing sorts IDs that finely (ADR-FND-04-002).
 */
class UuidV7Generator(
    private val clock: Clock,
    private val random: Random,
) {
    fun next(): Uuid {
        val millis = clock.millis()
        check(millis in 0 until TIMESTAMP_LIMIT) { "Clock reading $millis ms is outside the UUIDv7 timestamp range" }
        val bytes = random.nextBytes(RANDOM_BYTES)
        val randA = bytes.bigEndian(0, RAND_A_BYTES) and RAND_A_MASK
        val randB = bytes.bigEndian(RAND_A_BYTES, RANDOM_BYTES) and RAND_B_MASK
        val mostSignificant = (millis shl TIMESTAMP_SHIFT) or VERSION_BITS or randA
        val leastSignificant = VARIANT_BITS or randB
        return Uuid.fromLongs(mostSignificant, leastSignificant)
    }

    private fun ByteArray.bigEndian(
        from: Int,
        until: Int,
    ): Long = (from until until).fold(0L) { acc, i -> (acc shl Byte.SIZE_BITS) or (this[i].toLong() and BYTE_MASK) }

    private companion object {
        // 48-bit unix_ts_ms field: valid readings are 0 until 2^48 ms.
        const val TIMESTAMP_LIMIT = 1L shl 48
        const val TIMESTAMP_SHIFT = 16
        const val VERSION_BITS = 0x7000L
        const val VARIANT_BITS = Long.MIN_VALUE
        const val RANDOM_BYTES = 10
        const val RAND_A_BYTES = 2
        const val RAND_A_MASK = 0x0FFFL
        const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
        const val BYTE_MASK = 0xFFL
    }
}
