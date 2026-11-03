package io.github.pdroacordi.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Each rule must fail on a fixture that breaks it, so a rule that silently matches nothing cannot pass
 * (docs/knowledge/patterns/architecture-rule-with-violating-fixture.md).
 */
class RuleFixturesTest {
    @Test
    fun `ARCH-1 fails on a core file importing Spring and on a Reactor star import`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-core-imports-spring"))
        assertFlags(violations, "ARCH-1", "SpringImport.kt", "org.springframework.stereotype.Component")
        assertFlags(violations, "ARCH-1", "StarImport.kt", "reactor.core.publisher")
    }

    @Test
    fun `ARCH-1 fails on JDK network clients imported or written fully qualified, and on inline Reactor types`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-fully-qualified-jdk-client"))
        assertFlags(violations, "ARCH-1", "JdkClientImport.kt", "imports java.net.HttpURLConnection")
        assertFlags(violations, "ARCH-1", "InlineClients.kt", "references java.net.http.HttpClient")
        assertFlags(violations, "ARCH-1", "InlineClients.kt", "references java.net.Socket")
        assertFlags(violations, "ARCH-1", "InlineClients.kt", "references reactor.core.publisher.Mono")
    }

    @Test
    fun `ARCH-1 allows URI and ignores comments and string literals`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-fully-qualified-jdk-client"))
        assertTrue(violations.none { "AllowedUri.kt" in it }) { violations.toString() }
    }

    @Test
    fun `ARCH-2 fails on a core file importing the app`() {
        val violations = coreDoesNotImportOuterLayers(fixture("arch-2-core-imports-app"))
        assertFlags(violations, "ARCH-2", "ImportsApp.kt", "$BASE_PACKAGE.gateway.app.GatewayApplication")
    }

    @Test
    fun `ARCH-3 fails on a provider adapter importing the infra adapter`() {
        val violations = adaptersDoNotImportAppOrSiblings(fixture("arch-3-provider-imports-infra"))
        assertFlags(violations, "ARCH-3", "ImportsInfra.kt", "$BASE_PACKAGE.gateway.infra.redis.RedisRateLimiter")
    }

    @Test
    fun `ARCH-4 fails on an agent file importing the gateway`() {
        val violations = agentDoesNotImportGateway(fixture("arch-4-agent-imports-gateway"))
        assertFlags(violations, "ARCH-4", "ImportsGateway.kt", "$BASE_PACKAGE.gateway.core.ChatModelPort")
    }

    @Test
    fun `ARCH-5 fails on a cli file importing the agent runtime`() {
        val violations = clientDoesNotImportServers(fixture("arch-5-cli-imports-agent"))
        assertFlags(violations, "ARCH-5", "ImportsAgent.kt", "$BASE_PACKAGE.agent.core.AgentDefinition")
    }

    @Test
    fun `ARCH-6 fails on a Component outside an app module`() {
        val violations = wiringOnlyInApp(fixture("arch-6-component-outside-app"))
        assertFlags(violations, "ARCH-6", "RedisWiring.kt", "annotated with @org.springframework.stereotype.Component")
        assertFlags(violations, "ARCH-6", "RedisWiring.kt", "imports org.springframework.stereotype.Component")
    }

    @Test
    fun `ARCH-6 fails on a wiring annotation written fully qualified`() {
        val violations = wiringOnlyInApp(fixture("arch-6-fully-qualified"))
        assertFlags(
            violations,
            "ARCH-6",
            "QualifiedComponent.kt",
            "annotated with @org.springframework.stereotype.Component",
        )
    }

    @Test
    fun `ARCH-6 fails on a typealias of a wiring annotation`() {
        val violations = wiringOnlyInApp(fixture("arch-6-typealias"))
        assertFlags(violations, "ARCH-6", "AliasedService.kt", "aliases org.springframework.stereotype.Service")
    }

    @Test
    fun `ARCH-6 fails on wiring annotations reached through star imports`() {
        val violations = wiringOnlyInApp(fixture("arch-6-star-import"))
        assertFlags(
            violations,
            "ARCH-6",
            "StarConfiguration.kt",
            "annotated with @org.springframework.context.annotation.Configuration",
        )
        assertFlags(violations, "ARCH-6", "StarStereotype.kt", "annotated with @org.springframework.stereotype.Service")
    }

    @Test
    fun `ARCH-7 fails on a file whose package belongs to another module`() {
        val violations = packagesMatchModuleRoots(fixture("arch-7-wrong-package"))
        assertFlags(violations, "ARCH-7", "WrongPackage.kt", "$BASE_PACKAGE.agent.app")
    }

    @Test
    fun `ARCH-9 fails on a gateway module importing the agent runtime`() {
        val violations = importsStayWithinAllowedModules(fixture("arch-9-gateway-imports-agent"))
        assertFlags(violations, "ARCH-9", "ImportsAgentRuntime.kt", "imports $BASE_PACKAGE.agent.core.AgentDefinition")
    }

    @Test
    fun `a package prefix matches itself`() {
        assertTrue("org.springframework".isInPackage("org.springframework"))
    }

    @Test
    fun `a package prefix matches its sub-packages and star imports, whose name omits the star`() {
        assertTrue("org.springframework.stereotype.Component".isInPackage("org.springframework"))
        assertTrue("reactor.core.publisher".isInPackage("reactor"))
    }

    @Test
    fun `a package prefix stops at a segment boundary`() {
        assertFalse("$BASE_PACKAGE.gatewayx.core".isInPackage("$BASE_PACKAGE.gateway"))
        assertFalse("reactorx.core".isInPackage("reactor"))
    }

    // Konsist derives every fixture file's module as `architecture-tests`, so the module comes from the
    // fixture layout instead: <case>/<module>/src/main/kotlin/...
    private fun fixture(case: String): List<ModuleFile> {
        val caseDir = File(FIXTURES_DIR, case)
        val moduleDirs = caseDir.listFiles { file -> file.isDirectory }.orEmpty()
        check(moduleDirs.isNotEmpty()) { "no fixture modules in ${caseDir.absolutePath}" }
        return moduleDirs.flatMap { moduleDir ->
            Konsist
                .scopeFromDirectory("architecture-tests/$FIXTURES_DIR/$case/${moduleDir.name}")
                .files
                .map { ModuleFile(moduleDir.name, it) }
        }
    }

    private fun assertFlags(
        violations: List<String>,
        rule: String,
        fileName: String,
        offending: String,
    ) {
        assertTrue(violations.any { it.startsWith(rule) && fileName in it && offending in it }) {
            "expected $rule to flag $offending in $fileName, got: $violations"
        }
    }

    private companion object {
        // Relative to the test task's working directory, the architecture-tests project directory.
        const val FIXTURES_DIR = "src/fixtures/kotlin"
    }
}
