package io.github.pdroacordi.llmgateway.gateway.core.team

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class KeyHasherTest {
    private val hasher = KeyHasher("a-fake-hmac-secret-of-32-characters!".encodeToByteArray())

    @Test
    fun `the digest is HMAC-SHA-256, RFC 4231 test case 2`() {
        val digest = KeyHasher("Jefe".encodeToByteArray()).digest("what do ya want for nothing?")

        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", digest.hex)
    }

    @Test
    fun `another secret gives another digest`() {
        val other = KeyHasher("another-fake-secret-of-32-characters".encodeToByteArray())

        assertNotEquals(hasher.digest("token"), other.digest("token"))
    }

    @Test
    fun `the version defaults to 1 and is kept`() {
        assertEquals(1.toShort(), hasher.version)
        assertEquals(2.toShort(), KeyHasher("x".encodeToByteArray(), 2).version)
    }

    // Real threads are the point of the test, so the dispatcher is not injected.
    @Test
    @Suppress("InjectDispatcher")
    fun `concurrent digests from many threads agree, because each uses its own Mac`() {
        val tokens = List(64) { "token-$it" }
        val expected = tokens.associateWith { hasher.digest(it) }

        val rounds =
            runBlocking(Dispatchers.Default) {
                List(5_000) { round ->
                    async {
                        val token = tokens[round % tokens.size]
                        token to hasher.digest(token)
                    }
                }.awaitAll()
            }

        rounds.forEach { (token, digest) -> assertEquals(expected[token], digest) }
    }

    @Test
    fun `toString of a digest and of a secret hides the value`() {
        val digest = hasher.digest("token")
        val secret = TeamKeySecret("llmgw_plain-value")

        assertEquals("[digest]", digest.toString())
        assertEquals("[redacted]", secret.toString())
        assertFalse(digest.hex in "$digest ${listOf(digest)}")
        assertEquals("llmgw_plain-value", secret.reveal())
    }
}
