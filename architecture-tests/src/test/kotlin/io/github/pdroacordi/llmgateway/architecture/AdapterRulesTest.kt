package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class AdapterRulesTest {
    @Test
    fun `ARCH-3 adapters import neither the app nor another adapter of their family`() {
        assertNoViolations(adaptersDoNotImportAppOrSiblings(ProductionScope.files))
    }
}
