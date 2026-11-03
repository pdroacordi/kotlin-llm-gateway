package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class WiringRulesTest {
    @Test
    fun `ARCH-6 Spring wiring annotations appear only in app modules`() {
        assertNoViolations(wiringOnlyInApp(ProductionScope.files))
    }
}
