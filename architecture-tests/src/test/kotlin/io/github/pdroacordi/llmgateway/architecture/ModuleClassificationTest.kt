package io.github.pdroacordi.llmgateway.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleClassificationTest {
    @Test
    fun `ARCH-8 every module holding Kotlin sources is in the module table`() {
        assertNoViolations(unclassifiedModules(ProductionScope.files.map { it.module }))
    }

    @Test
    fun `the production scope sees the real modules and no fixture`() {
        val modules = ProductionScope.files.map { it.module }.toSet()
        assertTrue("gateway-app" in modules && "architecture-tests" in modules) { "modules seen: $modules" }
        assertTrue(ProductionScope.files.none { it.file.sourceSetName == "fixtures" })
    }

    @Test
    fun `a module missing from the table is reported`() {
        val violations = unclassifiedModules(listOf("gateway-core", "scratch-module"))
        assertEquals(1, violations.size, violations.toString())
        assertTrue("ARCH-8" in violations.single() && "scratch-module" in violations.single()) {
            violations.single()
        }
    }

    @Test
    fun `a module in the table is not reported`() {
        assertEquals(emptyList<String>(), unclassifiedModules(listOf("gateway-core", "cli", "architecture-tests")))
    }

    @Test
    fun `build-logic is not a module`() {
        assertEquals(emptyList<String>(), unclassifiedModules(listOf("build-logic")))
    }

    @Test
    fun `a nested module name uses forward slashes on every platform`() {
        val anyFile = ProductionScope.files.first().file
        assertEquals("tools/smoke", ModuleFile("tools\\smoke", anyFile, "main").module)
    }
}
