package io.github.pdroacordi.llmgateway.gateway.core.request

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ClientRequestIdTest {
    @Test
    fun `one character is accepted`() {
        assertEquals("a", ClientRequestId.parseOrNull("a")?.value)
    }

    @Test
    fun `512 characters are accepted`() {
        val raw = "x".repeat(ClientRequestId.MAX_LENGTH)

        assertEquals(raw, ClientRequestId.parseOrNull(raw)?.value)
    }

    @Test
    fun `an empty value is rejected`() {
        assertNull(ClientRequestId.parseOrNull(""))
    }

    @Test
    fun `513 characters are rejected`() {
        assertNull(ClientRequestId.parseOrNull("x".repeat(ClientRequestId.MAX_LENGTH + 1)))
    }

    @Test
    fun `space and tilde, the ends of printable ASCII, are accepted`() {
        assertEquals("a b~", ClientRequestId.parseOrNull("a b~")?.value)
    }

    @ParameterizedTest
    @ValueSource(strings = ["a\tb", "a\u007Fb", "café", "a\rb", "a\nb", "a\u0000b"])
    fun `control characters, DEL and non-ASCII are rejected`(raw: String) {
        assertNull(ClientRequestId.parseOrNull(raw))
    }
}
