package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class ModuleDependenciesTest {
    @Test
    fun `ARCH-9 each module imports only its own packages and those of the modules it may depend on`() {
        assertNoViolations(importsStayWithinAllowedModules(ProductionScope.files))
    }
}
