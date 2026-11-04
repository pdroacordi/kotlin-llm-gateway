package io.github.pdroacordi.llmgateway.gateway.core.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TokenCountTest {
    @Test
    fun `a count cannot be negative`() {
        assertThrows<IllegalArgumentException> { TokenCount(-1) }
    }

    @Test
    fun `zero is a count`() {
        assertEquals(0L, TokenCount(0).value)
    }

    @Test
    fun `counts add`() {
        assertEquals(TokenCount(5), TokenCount(2) + TokenCount(3))
    }

    @Test
    fun `a sum past Long says so, rather than going negative`() {
        assertThrows<ArithmeticException> { TokenCount(Long.MAX_VALUE) + TokenCount(1) }
    }

    @Test
    fun `total tokens is input plus output`() {
        assertEquals(TokenCount(16), Usage(TokenCount(14), TokenCount(2)).totalTokens)
    }
}
