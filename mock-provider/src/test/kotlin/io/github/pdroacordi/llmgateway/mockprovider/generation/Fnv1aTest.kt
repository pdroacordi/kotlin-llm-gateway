package io.github.pdroacordi.llmgateway.mockprovider.generation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

// Test vectors of draft-eastlake-fnv (64-bit FNV-1a).
class Fnv1aTest {
    @Test
    fun `the empty input hashes to the offset basis`() {
        assertEquals(0xcbf29ce484222325uL.toLong(), Fnv1a.hash64(""))
    }

    @Test
    fun `published vectors for a and foobar`() {
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), Fnv1a.hash64("a"))
        assertEquals(0x85944171f73967e8uL.toLong(), Fnv1a.hash64("foobar"))
    }

    @Test
    fun `the hash is over UTF-8 bytes`() {
        assertEquals(Fnv1a.hash64("é".encodeToByteArray()), Fnv1a.hash64("é"))
        assertNotEquals(Fnv1a.hash64("e"), Fnv1a.hash64("é"))
    }
}
