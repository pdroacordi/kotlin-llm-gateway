package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TestSupportRulesTest {
    @Test
    fun `ARCH-11 Testcontainers and test-support appear only in integrationTest sources and test-support's main`() {
        assertNoViolations(testcontainersOnlyInIntegrationTests(ProductionScope.files))
    }

    @Test
    fun `Konsist names the integrationTest source set after its directory`() {
        val sourceSets =
            ProductionScope.files
                .filter { it.path.endsWith("/MockProviderImageTest.kt") || it.path.endsWith("/StackContractTest.kt") }
                .associate { it.path.substringAfterLast('/') to it.sourceSet }
        assertEquals(
            mapOf("MockProviderImageTest.kt" to "integrationTest", "StackContractTest.kt" to "test"),
            sourceSets,
        )
    }
}
