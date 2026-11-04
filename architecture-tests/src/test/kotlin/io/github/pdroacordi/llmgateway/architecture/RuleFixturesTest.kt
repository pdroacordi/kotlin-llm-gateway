package io.github.pdroacordi.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `ARCH-1 sees code after a string, comment, name or char that the old stripping took for an opening`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-fully-qualified-jdk-client"))
        assertFlags(violations, "ARCH-1", "GlobInString.kt", "references java.net.http.HttpClient")
        assertFlags(violations, "ARCH-1", "GlobInLineComment.kt", "references java.net.Socket")
        assertFlags(violations, "ARCH-1", "QuoteInBacktickName.kt", "references java.net.Socket")
        assertFlags(violations, "ARCH-1", "QuoteChar.kt", "references java.net.Socket")
        assertFlags(violations, "ARCH-1", "QuoteInComments.kt", "references java.net.Socket")
        assertFlags(violations, "ARCH-1", "QuoteInComments.kt", "references java.net.http.HttpClient")
    }

    @Test
    fun `ARCH-1 fails on NIO channels and the JDK's own server and socket extensions`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-fully-qualified-jdk-client"))
        assertFlags(violations, "ARCH-1", "NioAndJdkServers.kt", "imports java.nio.channels.SocketChannel")
        assertFlags(violations, "ARCH-1", "NioAndJdkServers.kt", "references com.sun.net.httpserver.HttpServer")
        assertFlags(violations, "ARCH-1", "NioAndJdkServers.kt", "references jdk.net.ExtendedSocketOptions")
    }

    @Test
    fun `ARCH-1 ignores what literals, comments and backtick names hold, and string templates stay hidden`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-fully-qualified-jdk-client"))
        assertTrue(violations.none { "AllowedLiterals.kt" in it }) { violations.toString() }
        assertTrue(violations.none { "HiddenTemplate.kt" in it }) { violations.toString() }
    }

    @Test
    fun `ARCH-1 fails on a JSON library or a provider SDK in core`() {
        val violations = coreImportsArePlainKotlin(fixture("arch-1-core-imports-json-library"))
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "imports tools.jackson.databind.JsonNode")
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "imports com.fasterxml.jackson.databind.ObjectMapper")
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "imports kotlinx.serialization.json.JsonElement")
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "imports com.openai.client.OpenAIClient")
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "imports com.anthropic.client.AnthropicClient")
        assertFlags(violations, "ARCH-1", "JacksonInCore.kt", "references tools.jackson.databind.node.ObjectNode")
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
    fun `ARCH-9 fails on a core integration test importing test-support`() {
        val violations = importsStayWithinAllowedModules(fixture(ARCH_9_TEST_SUPPORT))
        assertFlags(
            violations,
            "ARCH-9",
            "UsesSharedPostgres.kt",
            "imports $BASE_PACKAGE.testsupport.SharedPostgres",
        )
    }

    @Test
    fun `ARCH-9 allows test-support only in the integration tests of a module whose testDependsOn lists it`() {
        val violations = importsStayWithinAllowedModules(fixture(ARCH_9_TEST_SUPPORT))
        assertTrue(violations.none { "AllowedSharedRedis.kt" in it }) { violations.toString() }
        assertFlags(
            violations,
            "ARCH-9",
            "SharedRedisInUnitTest.kt",
            "references $BASE_PACKAGE.testsupport.SharedRedis",
        )
    }

    @Test
    fun `ARCH-11 fails on Testcontainers imported or written inline in a unit test`() {
        val violations = testcontainersOnlyInIntegrationTests(fixture(ARCH_11_UNIT_TEST))
        assertFlags(
            violations,
            "ARCH-11",
            "ContainerInUnitTest.kt",
            "imports org.testcontainers.containers.GenericContainer",
        )
        assertFlags(
            violations,
            "ARCH-11",
            "ContainerInUnitTest.kt",
            "references org.testcontainers.utility.DockerImageName",
        )
        assertFlags(
            violations,
            "ARCH-11",
            "ContainerInOwnUnitTest.kt",
            "imports org.testcontainers.utility.DockerImageName",
        )
        assertTrue(violations.all { "ADR-016, ADR-017" in it }) { violations.toString() }
    }

    @Test
    fun `ARCH-11 allows Testcontainers in integration tests and in test-support's main`() {
        val violations = testcontainersOnlyInIntegrationTests(fixture(ARCH_11_UNIT_TEST))
        assertTrue(violations.none { "AllowedContainer.kt" in it || "AllowedFixture.kt" in it }) {
            violations.toString()
        }
    }

    @Test
    fun `ARCH-11 fails on Testcontainers in a core module's integration tests and unit tests`() {
        val violations = testcontainersOnlyInIntegrationTests(fixture("arch-11-testcontainers-in-core"))
        assertFlags(violations, "ARCH-11", "CoreContainer.kt", "imports org.testcontainers.containers.GenericContainer")
        assertFlags(
            violations,
            "ARCH-11",
            "InlineCoreContainer.kt",
            "references org.testcontainers.utility.DockerImageName",
        )
    }

    @Test
    fun `ARCH-11 fails on test-support referenced from production code`() {
        val violations = testcontainersOnlyInIntegrationTests(fixture("arch-11-test-support-in-main"))
        assertFlags(violations, "ARCH-11", "FixtureInMain.kt", "imports $BASE_PACKAGE.testsupport.SharedRedis")
    }

    @Test
    fun `a fixture file's source set is the segment after its module's src directory`() {
        val sourceSets = fixture(ARCH_11_UNIT_TEST).associate { it.path.substringAfterLast('/') to it.sourceSet }
        assertEquals(
            mapOf(
                "ContainerInUnitTest.kt" to "test",
                "AllowedContainer.kt" to "integrationTest",
                "AllowedFixture.kt" to "main",
                "ContainerInOwnUnitTest.kt" to "test",
            ),
            sourceSets,
        )
    }

    @Test
    fun `the source set is read from a path with either separator`() {
        val windows = "\\architecture-tests\\src\\fixtures\\kotlin\\c\\gateway-app\\src\\integrationTest\\kotlin\\X.kt"
        val linux = "/gateway-app/src/test/kotlin/io/X.kt"
        assertEquals("integrationTest", sourceSetOf("gateway-app", windows))
        assertEquals("test", sourceSetOf("gateway-app", linux))
    }

    @Test
    fun `a path outside the module's src directory has no source set`() {
        assertEquals("", sourceSetOf("gateway-app", "/gateway-core/src/main/kotlin/X.kt"))
    }

    @Test
    fun `ARCH-10 fails on error handling outside an app's error package`() {
        val violations = errorHandlingOnlyInErrorPackage(fixture(ARCH_10))
        val annotation = "org.springframework.web.bind.annotation"
        assertFlags(violations, "ARCH-10", "ChatErrorAdvice.kt", "imports $annotation.RestControllerAdvice")
        assertFlags(violations, "ARCH-10", "ChatErrorAdvice.kt", "annotated with @$annotation.ExceptionHandler")
        assertFlags(violations, "ARCH-10", "StarImportedAdvice.kt", "annotated with @$annotation.ControllerAdvice")
        assertFlags(
            violations,
            "ARCH-10",
            "LocalExceptionHandler.kt",
            "uses org.springframework.web.server.WebExceptionHandler through a star import",
        )
        assertFlags(
            violations,
            "ARCH-10",
            "LocalExceptionHandler.kt",
            "references org.springframework.boot.webflux.error.ErrorWebExceptionHandler",
        )
    }

    @Test
    fun `ARCH-10 fails on the coroutine handler, Boot's problem-details advice and the exception-handling decorator`() {
        val violations = errorHandlingOnlyInErrorPackage(fixture(ARCH_10))
        assertFlags(
            violations,
            "ARCH-10",
            "CoroutineErrorHandler.kt",
            "imports org.springframework.web.server.CoWebExceptionHandler",
        )
        assertFlags(
            violations,
            "ARCH-10",
            "ProblemDetailsWiring.kt",
            "imports org.springframework.boot.webflux.autoconfigure.ProblemDetailsExceptionHandler",
        )
        assertFlags(
            violations,
            "ARCH-10",
            "WrappedWebHandler.kt",
            "references org.springframework.web.server.handler.ExceptionHandlingWebHandler",
        )
    }

    @Test
    fun `ARCH-10 allows error handling in the app's error package`() {
        val violations = errorHandlingOnlyInErrorPackage(fixture(ARCH_10))
        assertTrue(violations.none { "AllowedHandler.kt" in it }) { violations.toString() }
    }

    @Test
    fun `ARCH-12 fails on an adapter imported or written inline outside the app's config package`() {
        val violations = adaptersReferencedOnlyFromWiring(fixture(ARCH_12))
        val openAi = "$BASE_PACKAGE.gateway.providers.openai"
        assertFlags(violations, "ARCH-12", "LeakyHandler.kt", "imports $openAi.OpenAiChatAdapter")
        assertFlags(violations, "ARCH-12", "LeakyHandler.kt", "references $openAi.OpenAiAdapterConfig")
        val anthropic = "$BASE_PACKAGE.gateway.providers.anthropic"
        assertFlags(violations, "ARCH-12", "LeakyHandler.kt", "imports $anthropic.AnthropicChatAdapter")
    }

    @Test
    fun `ARCH-12 fails on a typealias of an adapter type that another package can import`() {
        val violations = adaptersReferencedOnlyFromWiring(fixture(ARCH_12))
        val openAi = "$BASE_PACKAGE.gateway.providers.openai"
        assertFlags(
            violations,
            "ARCH-12",
            "AdapterAlias.kt",
            "typealias Adapter, which aliases $openAi.OpenAiChatAdapter",
        )
        assertFlags(
            violations,
            "ARCH-12",
            "AdapterAlias.kt",
            "typealias QualifiedConfig, which aliases $openAi.OpenAiAdapterConfig",
        )
    }

    @Test
    fun `ARCH-12 allows a private typealias in the config package`() {
        val violations = adaptersReferencedOnlyFromWiring(fixture(ARCH_12))
        assertTrue(violations.none { "LocalAdapter" in it }) { violations.toString() }
    }

    @Test
    fun `ARCH-12 allows an adapter in the app's config package and the JSON conversion anywhere`() {
        val violations = adaptersReferencedOnlyFromWiring(fixture(ARCH_12))
        assertTrue(violations.none { "AllowedWiring.kt" in it || "AllowedJsonHelper.kt" in it }) {
            violations.toString()
        }
    }

    @Test
    fun `ARCH-13 fails on a provider adapter importing or writing inline another provider's type`() {
        val violations = providerAdaptersDoNotNameEachOther(fixture(ARCH_13))
        val providers = "$BASE_PACKAGE.gateway.providers"
        assertFlags(violations, "ARCH-13", "ImportsOpenAi.kt", "imports $providers.openai.OpenAiFailureClassifier")
        assertFlags(
            violations,
            "ARCH-13",
            "NamesAnthropic.kt",
            "references $providers.anthropic.AnthropicAdapterConfig",
        )
    }

    @Test
    fun `ARCH-13 allows the shared packages to be named`() {
        val violations = providerAdaptersDoNotNameEachOther(fixture(ARCH_13))
        assertTrue(violations.none { "AllowedShared.kt" in it }) { violations.toString() }
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

    // Konsist derives every fixture file's module as `architecture-tests` and its source set as `fixtures`, so both
    // come from the fixture layout instead: <case>/<module>/src/<source set>/kotlin/...
    private fun fixture(case: String): List<ModuleFile> {
        val caseDir = File(FIXTURES_DIR, case)
        val moduleDirs = caseDir.listFiles { file -> file.isDirectory }.orEmpty()
        check(moduleDirs.isNotEmpty()) { "no fixture modules in ${caseDir.absolutePath}" }
        return moduleDirs.flatMap { moduleDir ->
            Konsist
                .scopeFromDirectory("architecture-tests/$FIXTURES_DIR/$case/${moduleDir.name}")
                .files
                .map { ModuleFile(moduleDir.name, it, sourceSetOf(moduleDir.name, it.projectPath)) }
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
        const val ARCH_10 = "arch-10-error-handling-outside-error-package"
        const val ARCH_12 = "arch-12-adapter-outside-wiring"
        const val ARCH_13 = "arch-13-provider-names-provider"
        const val ARCH_9_TEST_SUPPORT = "arch-9-core-imports-test-support"
        const val ARCH_11_UNIT_TEST = "arch-11-testcontainers-in-unit-test"
    }
}
