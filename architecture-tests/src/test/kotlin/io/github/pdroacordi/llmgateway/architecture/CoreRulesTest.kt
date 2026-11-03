package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class CoreRulesTest {
    @Test
    fun `ARCH-1 core modules import only plain Kotlin`() {
        assertNoViolations(coreImportsArePlainKotlin(ProductionScope.files))
    }

    @Test
    fun `ARCH-2 core modules import nothing from the adapters or apps of their family`() {
        assertNoViolations(coreDoesNotImportOuterLayers(ProductionScope.files))
    }
}
