package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class PackageLayoutTest {
    @Test
    fun `ARCH-7 every file's package starts with its module's root package`() {
        assertNoViolations(packagesMatchModuleRoots(ProductionScope.files))
    }
}
