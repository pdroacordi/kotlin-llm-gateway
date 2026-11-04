package io.github.pdroacordi.llmgateway.gateway.app.security

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class ExposureGuardTest {
    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "127.1.2.3", "::1", "[::1]", "0:0:0:0:0:0:0:1", "localhost", "LOCALHOST"])
    fun `a loopback address passes without the flag`(address: String) {
        assertTrue(ExposureGuard.isLoopback(address))
        assertDoesNotThrow { ExposureGuard.check(address, allowExposure = false) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0", "::", "192.168.1.10", "10.0.0.1", "example.com", "127.0.0.1.example.com", ""])
    fun `anything else stops the start, naming the property`(address: String) {
        assertFalse(ExposureGuard.isLoopback(address))
        val failure = assertThrows<IllegalStateException> { ExposureGuard.check(address, allowExposure = false) }

        assertTrue("llmgw.security.allow-unauthenticated-exposure" in failure.message.orEmpty()) { failure.message }
    }

    @Test
    fun `an absent address means every interface, so it stops the start too`() {
        assertThrows<IllegalStateException> { ExposureGuard.check(null, allowExposure = false) }
    }

    @Test
    fun `with the flag a non-loopback bind starts with one warning`(output: CapturedOutput) {
        ExposureGuard.check("0.0.0.0", allowExposure = true)

        assertTrue(output.all.lines().count { "allow-unauthenticated-exposure" in it } == 1) { output.all }
    }

    @Test
    fun `with the flag a loopback bind starts with the warning as well, since the Host check is off`(
        output: CapturedOutput,
    ) {
        ExposureGuard.check("127.0.0.1", allowExposure = true)

        assertTrue("allow-unauthenticated-exposure" in output.all) { output.all }
    }
}
