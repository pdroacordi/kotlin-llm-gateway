package io.github.pdroacordi.llmgateway.gateway.core.team

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// Every row of the Authorization table of GW-04 section 4 that needs no store.
class BearerCredentialTest {
    @Test
    fun `no header is Missing`() {
        assertEquals(BearerCredential.Missing, BearerCredential.parse(emptyList()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Basic abc", "Token abc", "abc", "", "Bearerabc"])
    fun `one line with another scheme is Missing`(line: String) {
        assertEquals(BearerCredential.Missing, BearerCredential.parse(listOf(line)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Bearer", "Bearer ", "bearer   "])
    fun `Bearer with an empty token is Missing`(line: String) {
        assertEquals(BearerCredential.Missing, BearerCredential.parse(listOf(line)))
    }

    @Test
    fun `more than one line is Ambiguous, even when they agree`() {
        assertEquals(BearerCredential.Ambiguous, BearerCredential.parse(listOf("Bearer a", "Bearer a")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Bearer abc", "bearer abc", "BEARER abc", "bEaReR abc"])
    fun `the scheme is compared ignoring case`(line: String) {
        assertEquals(BearerCredential.Token("abc"), BearerCredential.parse(listOf(line)))
    }

    @Test
    fun `two tokens on one line stay one token, for the format check to refuse`() {
        assertEquals(BearerCredential.Token("a, Bearer b"), BearerCredential.parse(listOf("Bearer a, Bearer b")))
    }

    @Test
    fun `toString of a token hides it`() {
        assertEquals("Token([redacted])", BearerCredential.Token("abc").toString())
    }
}
