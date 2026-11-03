package io.github.pdroacordi.llmgateway.mockprovider.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HeaderRedactionTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "authorization", "Proxy-Authorization", "x-api-key", "api-key", "cookie", "Set-Cookie",
            "x-auth-thing", "X-Session-Token", "client-secret", "x-password", "x-my-cookie-jar", "OpenAI-Key",
        ],
    )
    fun `secret-looking names are redacted, every value`(name: String) {
        val redacted = HeaderRedaction.redact(mapOf(name to listOf("v1", "v2")))

        assertEquals(mapOf(name.lowercase() to listOf("[redacted]", "[redacted]")), redacted)
    }

    @ParameterizedTest
    @ValueSource(strings = ["traceparent", "tracestate", "x-client-request-id", "anthropic-version", "content-type"])
    fun `other names keep their values, lowercased`(name: String) {
        assertEquals(mapOf(name to listOf("value")), HeaderRedaction.redact(mapOf(name.uppercase() to listOf("value"))))
    }

    @Test
    fun `the fingerprint is the first 8 hex digits of SHA-256`() {
        // printf '%s' sk-test-abc | sha256sum
        assertEquals("e1c3928a", CredentialFingerprint().of("sk-test-abc"))
        assertEquals("3dcad332", CredentialFingerprint().of("sk-other"))
    }

    @Test
    fun `a bearer token wins over x-api-key, and blank values are no credential`() {
        assertEquals(PresentedCredential(Scheme.BEARER, "sk-a"), PresentedCredential.of("Bearer sk-a", "k"))
        assertEquals(PresentedCredential(Scheme.BEARER, "sk-a"), PresentedCredential.of("bearer  sk-a ", null))
        assertEquals(PresentedCredential(Scheme.X_API_KEY, "k"), PresentedCredential.of(null, "k"))
        assertEquals(PresentedCredential(Scheme.X_API_KEY, "k"), PresentedCredential.of("Basic abc", "k"))
        assertNull(PresentedCredential.of("Bearer ", ""))
        assertNull(PresentedCredential.of("Basic abc", null))
        assertNull(PresentedCredential.of(null, null))
    }
}
