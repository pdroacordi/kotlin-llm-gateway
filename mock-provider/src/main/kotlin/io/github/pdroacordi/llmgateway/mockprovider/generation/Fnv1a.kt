package io.github.pdroacordi.llmgateway.mockprovider.generation

/** 64-bit FNV-1a (draft-eastlake-fnv): pure Kotlin, so seeding needs no JCA lookup on the event loop. */
object Fnv1a {
    private const val OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L
    private const val BYTE_MASK = 0xff

    fun hash64(text: String): Long = hash64(text.encodeToByteArray())

    fun hash64(bytes: ByteArray): Long {
        var hash = OFFSET_BASIS
        for (byte in bytes) {
            hash = (hash xor (byte.toInt() and BYTE_MASK).toLong()) * PRIME
        }
        return hash
    }
}
