package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Test

class ErrorHandlingRulesTest {
    @Test
    fun `ARCH-10 error handling lives only in an app module's error package`() {
        assertNoViolations(errorHandlingOnlyInErrorPackage(ProductionScope.files))
    }
}
