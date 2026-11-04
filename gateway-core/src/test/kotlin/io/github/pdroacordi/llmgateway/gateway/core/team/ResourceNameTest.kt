package io.github.pdroacordi.llmgateway.gateway.core.team

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class ResourceNameTest {
    @ParameterizedTest
    @MethodSource("valid")
    fun `a lowercase name of 1 to 64 characters is accepted`(name: String) {
        assertEquals(name, ResourceName.parseOrNull(name)?.value)
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun `anything else is refused`(name: String) {
        assertNull(ResourceName.parseOrNull(name))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a\n", "a\r\n"])
    fun `a trailing newline is refused, which a dollar anchor alone would pass`(name: String) {
        assertNull(ResourceName.parseOrNull(name))
    }

    companion object {
        @JvmStatic
        fun valid(): List<String> = listOf("a", "0", "payments", "ci.prod-1_a", "a".repeat(64))

        @JvmStatic
        fun invalid(): List<String> =
            listOf("", "A", "Payments", "-a", ".a", "_a", "a b", "a/b", "ação", "a".repeat(65))
    }
}
