package io.github.pdroacordi.llmgateway.gateway.providers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ApiKeyTest {
    @Test
    fun `toString never prints the key`() {
        assertEquals("[redacted]", ApiKey("sk-canary-key").toString())
    }

    @Test
    fun `a string template built from the key prints no key either`() {
        assertFalse("sk-canary-key" in "key=${ApiKey("sk-canary-key")}")
    }

    @Test
    fun `reveal is the one way to the value`() {
        assertEquals("sk-canary-key", ApiKey("sk-canary-key").reveal())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "\t\n"])
    fun `a blank key is blank`(value: String) {
        assertTrue(ApiKey(value).isBlank)
    }

    @ParameterizedTest
    @ValueSource(strings = ["sk-key\n", "sk-key\r", "sk key", "sk\tkey", "sk-cl\u00e9", "sk-\u0001", "sk-\u007f"])
    fun `a key that cannot be a header value is not visible ASCII`(value: String) {
        assertFalse(ApiKey(value).isVisibleAscii)
    }

    @Test
    fun `every visible ASCII character is allowed`() {
        assertTrue(ApiKey((0x21..0x7E).map { it.toChar() }.joinToString("")).isVisibleAscii)
    }

    @Test
    fun `a key is not blank`() {
        assertFalse(ApiKey("k").isBlank)
    }
}
