package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class AdapterRulesTest {
    @Test
    fun `ARCH-3 adapters import neither the app nor another adapter of their family`() {
        assertNoViolations(adaptersDoNotImportAppOrSiblings(ProductionScope.files))
    }

    @Test
    fun `ARCH-12 an app's main code names an adapter only in its config package`() {
        assertNoViolations(adaptersReferencedOnlyFromWiring(ProductionScope.files))
    }
}
