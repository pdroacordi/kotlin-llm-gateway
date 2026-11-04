package io.github.pdroacordi.llmgateway.gateway.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.DrbgParameters

class KeyDrbgTest {
    @Test
    fun `the key DRBG runs at strength 256, and not the default 128`() {
        val parameters = seededKeyDrbg().parameters as DrbgParameters.Instantiation

        assertEquals(256, parameters.strength)
    }
}
