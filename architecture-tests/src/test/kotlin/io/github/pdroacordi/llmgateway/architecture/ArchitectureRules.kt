package io.github.pdroacordi.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoAnnotationDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider

/**
 * A Kotlin file, the module it belongs to and its source set (`main`, `test`, `integrationTest`). Both are explicit
 * because Konsist derives them from the path, which is wrong for fixtures (all in `architecture-tests`, `fixtures`).
 */
class ModuleFile(
    module: String,
    val file: KoFileDeclaration,
    val sourceSet: String,
) {
    // Konsist uses the platform separator
    // (docs/knowledge/gotchas/konsist-paths-and-module-names-use-platform-separator.md).
    val module: String = module.replace('\\', '/')
    val path: String = file.projectPath.replace('\\', '/').removePrefix("/")
}

/** Segment-boundary match: `a.b` matches `a.b` and `a.b.c`, not `a.bc`. A star import's name omits the star. */
fun String.isInPackage(prefix: String): Boolean = this == prefix || startsWith("$prefix.")

// CLAUDE.md "Core modules are plain Kotlin"; adding a family here needs a local ADR (ADR-FND-01-007).
// java.net and javax.net are on every classpath, so only these rules keep sockets and HTTP clients out of core.
val CORE_FORBIDDEN_PACKAGES: List<String> =
    listOf(
        "org.springframework",
        "reactor",
        "io.netty",
        "io.r2dbc",
        "java.sql",
        "javax.sql",
        "org.jooq",
        "io.lettuce",
        "redis.clients",
        "org.apache.kafka",
        "io.opentelemetry.sdk",
        "io.opentelemetry.exporter",
        "java.net",
        "javax.net",
        "okhttp3",
        "io.ktor.client",
        "org.apache.hc",
        "kotlinx.coroutines.reactor",
        "kotlinx.coroutines.reactive",
    )

// Value types from java.net that open no connection.
val CORE_ALLOWED_EXCEPTIONS: List<String> =
    listOf("java.net.URI", "java.net.URISyntaxException", "java.net.URLEncoder", "java.net.URLDecoder")

val WIRING_ANNOTATIONS: List<String> =
    listOf(
        "org.springframework.stereotype",
        "org.springframework.context.annotation.Configuration",
        "org.springframework.context.annotation.Bean",
        "org.springframework.boot.autoconfigure.SpringBootApplication",
        "org.springframework.web.bind.annotation.RestController",
        "org.springframework.boot.context.properties.ConfigurationProperties",
    )

// ADR-015, ADR-FND-04-005: one mapper per app module. A class name also matches its nested types; the Boot
// autoconfigure entry is a whole package.
val ERROR_HANDLING_TYPES: List<String> =
    listOf(
        "org.springframework.web.server.WebExceptionHandler",
        "org.springframework.web.server.CoWebExceptionHandler",
        "org.springframework.web.server.handler.ExceptionHandlingWebHandler",
        "org.springframework.web.server.handler.ResponseStatusExceptionHandler",
        "org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler",
        "org.springframework.web.reactive.DispatchExceptionHandler",
        "org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler",
        "org.springframework.boot.webflux.error.ErrorWebExceptionHandler",
        "org.springframework.boot.webflux.autoconfigure.ProblemDetailsExceptionHandler",
        "org.springframework.boot.webflux.autoconfigure.error",
        "org.springframework.web.bind.annotation.ControllerAdvice",
        "org.springframework.web.bind.annotation.RestControllerAdvice",
        "org.springframework.web.bind.annotation.ExceptionHandler",
    )

private const val FIXTURES_SOURCE_SET = "fixtures"
private const val MAIN_SOURCE_SET = "main"
private const val INTEGRATION_TEST_SOURCE_SET = "integrationTest"
private const val TESTCONTAINERS_PACKAGE = "org.testcontainers"
private val NOT_MODULES = setOf("build-logic")

/** ARCH-1: imports and fully qualified references written inline. */
fun coreImportsArePlainKotlin(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        .filter { modules.named(it.module)?.role == Role.CORE }
        .flatMap { file ->
            forbiddenReferences(file, "ARCH-1", "ADR-005, ADR-006") { name ->
                CORE_FORBIDDEN_PACKAGES.any { name.isInPackage(it) } &&
                    CORE_ALLOWED_EXCEPTIONS.none { name.isInPackage(it) }
            }
        }

/** ARCH-2: dependencies point inward. */
fun coreDoesNotImportOuterLayers(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files.flatMap { file ->
        val module = modules.named(file.module)
        if (module?.role != Role.CORE) {
            emptyList()
        } else {
            val outer = modules.filter { it.family == module.family && it.role in setOf(Role.ADAPTER, Role.APP) }
            forbiddenReferences(file, "ARCH-2", "ADR-005", inAnyOf(outer.map { it.rootPackage }))
        }
    }

/** ARCH-3. */
fun adaptersDoNotImportAppOrSiblings(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files.flatMap { file ->
        val module = modules.named(file.module)
        if (module?.role != Role.ADAPTER) {
            emptyList()
        } else {
            val others =
                modules.filter {
                    it.family == module.family && it != module && it.role in setOf(Role.ADAPTER, Role.APP)
                }
            forbiddenReferences(file, "ARCH-3", "ADR-005", inAnyOf(others.map { it.rootPackage }))
        }
    }

/** ARCH-4: the agent runtime is a client of the gateway. */
fun agentDoesNotImportGateway(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        .filter { modules.named(it.module)?.family == "agent" }
        .flatMap { forbiddenReferences(it, "ARCH-4", "ADR-011", inAnyOf(listOf("$BASE_PACKAGE.gateway"))) }

/** ARCH-5: the CLI talks only HTTP. */
fun clientDoesNotImportServers(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        .filter { modules.named(it.module)?.role == Role.CLIENT }
        .flatMap {
            forbiddenReferences(
                it,
                "ARCH-5",
                "ADR-012",
                inAnyOf(listOf("$BASE_PACKAGE.gateway", "$BASE_PACKAGE.agent")),
            )
        }

/**
 * ARCH-6: wiring lives in app modules; a module missing from the table is not an app either. Konsist resolves
 * annotation names through explicit imports only, so inline, star-imported and typealiased spellings are
 * resolved here (docs/knowledge/gotchas/konsist-annotation-fqn-unresolved-for-qualified-star-typealias.md).
 */
fun wiringOnlyInApp(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        .filter { modules.named(it.module)?.role != Role.APP }
        .flatMap { wiringIn(it) }

/** ARCH-7: makes the package-keyed rules ARCH-2 to ARCH-5 and ARCH-9 sound. */
fun packagesMatchModuleRoots(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files.mapNotNull { file ->
        val root = modules.named(file.module)?.rootPackage
        val pkg = file.file.packagee?.name
        when {
            root == null -> null
            pkg != null && pkg.isInPackage(root) -> null
            else -> "ARCH-7 (ADR-014): ${file.path} declares package ${pkg ?: "<none>"}, outside $root"
        }
    }

/** ARCH-8: a module cannot sit outside every rule. */
fun unclassifiedModules(
    observed: Collection<String>,
    modules: List<Module> = Modules.all,
): List<String> =
    observed
        .distinct()
        .filter { it !in NOT_MODULES && modules.named(it) == null }
        .sorted()
        .map { "ARCH-8 (ADR-014): module $it holds Kotlin sources but is not in the module table (Modules.kt)" }

/**
 * ARCH-9: a module references only its own packages and those of the modules in its `dependsOn`, plus those in its
 * `testDependsOn` from an `integrationTest` source set (ADR-017).
 */
fun importsStayWithinAllowedModules(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files.flatMap { file ->
        val module = modules.named(file.module)
        if (module == null) {
            emptyList()
        } else {
            val testOnly = if (file.sourceSet == INTEGRATION_TEST_SOURCE_SET) module.testDependsOn else emptyList()
            val allowed =
                listOf(module.rootPackage) +
                    (module.dependsOn + testOnly).mapNotNull { modules.named(it)?.rootPackage }
            forbiddenReferences(file, "ARCH-9", "ADR-005, ADR-014, ADR-017") { name ->
                name.isInPackage(BASE_PACKAGE) && allowed.none { name.isInPackage(it) }
            }
        }
    }

/**
 * ARCH-11: tests that need Docker live in `src/integrationTest` (ADR-016). `org.testcontainers` is referenced only
 * from `integrationTest` source sets of non-core modules and from test-support's `main`; test-support's packages only
 * from `integrationTest` source sets of other modules (ADR-017).
 */
fun testcontainersOnlyInIntegrationTests(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        // Core tests are plain JUnit (CLAUDE.md, Testing), so a core module's integrationTest gets no containers.
        .filterNot { it.sourceSet == INTEGRATION_TEST_SOURCE_SET && modules.named(it.module)?.role != Role.CORE }
        .flatMap { file ->
            val containersAllowed = file.module == TEST_SUPPORT && file.sourceSet == MAIN_SOURCE_SET
            val testSupportRoot = modules.named(TEST_SUPPORT)?.rootPackage ?: "$BASE_PACKAGE.testsupport"
            forbiddenReferences(file, "ARCH-11", "ADR-016, ADR-017") { name ->
                (!containersAllowed && name.isInPackage(TESTCONTAINERS_PACKAGE)) ||
                    (file.module != TEST_SUPPORT && name.isInPackage(testSupportRoot))
            }
        }

/**
 * The source set of a file at [path] in [module]: the path segment after `<module>/src/`, or empty when there is
 * none. Fixtures need it because Konsist names their source set `fixtures`.
 */
fun sourceSetOf(
    module: String,
    path: String,
): String {
    val normalized = path.replace('\\', '/')
    val marker = "/${module.replace('\\', '/')}/src/"
    val start = normalized.lastIndexOf(marker)
    return if (start < 0) "" else normalized.substring(start + marker.length).substringBefore('/')
}

/**
 * ARCH-10: error handling lives only in `<app root>.error`, so a `@ControllerAdvice` cannot silently take over
 * controller and handler-mapping errors. Annotations are resolved as in ARCH-6. Names are matched, not types: a
 * typealias or subclass declared inside `.error` and used elsewhere is not seen, so ErrorEnvelopeIntegrationTest
 * also asserts the exact set of WebExceptionHandler beans and that no advice bean exists.
 */
fun errorHandlingOnlyInErrorPackage(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files
        .filterNot { it.isInAppErrorPackage(modules) }
        .flatMap { errorHandlingIn(it) }

/** Every Kotlin file of the repository except fixtures, `build-logic/` and generated code under `build/`. */
object ProductionScope {
    val files: List<ModuleFile> by lazy {
        Konsist
            .scopeFromProject()
            .files
            .map { ModuleFile(it.moduleName, it, it.sourceSetName) }
            .filterNot {
                it.file.sourceSetName == FIXTURES_SOURCE_SET ||
                    it.module in NOT_MODULES ||
                    "/build/" in "/${it.path}"
            }
    }
}

fun assertNoViolations(violations: List<String>) {
    check(violations.isEmpty()) { violations.joinToString(separator = "\n", prefix = "\n") }
}

/** Dotted names written in code, outside import and package lines, comments and string literals. */
fun inlineQualifiedNames(text: String): Set<String> = QUALIFIED_NAME.findAll(codeOnly(text)).map { it.value }.toSet()

/** Undotted capitalized names written in code, the candidates a star import can resolve. */
fun simpleTypeNames(text: String): Set<String> = SIMPLE_TYPE_NAME.findAll(codeOnly(text)).map { it.value }.toSet()

private fun codeOnly(text: String): String =
    text
        .replace(RAW_STRING, "\"\"")
        .replace(BLOCK_COMMENT, " ")
        .replace(STRING, "\"\"")
        .replace(LINE_COMMENT, "")
        .replace(IMPORT_OR_PACKAGE_LINE, "")

private val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val LINE_COMMENT = Regex("//.*")
private val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(import|package)\s.*$""", RegexOption.MULTILINE)
private val QUALIFIED_NAME = Regex("""(?<![\w.])[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+""")
private val SIMPLE_TYPE_NAME = Regex("""(?<![\w.])[A-Z]\w*""")

private fun inAnyOf(prefixes: List<String>): (String) -> Boolean = { name -> prefixes.any { name.isInPackage(it) } }

private fun forbiddenReferences(
    file: ModuleFile,
    rule: String,
    adr: String,
    isForbidden: (String) -> Boolean,
): List<String> {
    val imports =
        file.file.imports
            .map { it.name }
            .filter(isForbidden)
            .map { "$rule ($adr): ${file.path} in ${file.module} imports $it" }
    val inline =
        inlineQualifiedNames(file.file.text)
            .filter(isForbidden)
            .sorted()
            .map { "$rule ($adr): ${file.path} in ${file.module} references $it" }
    return imports + inline
}

private fun isWiring(name: String): Boolean = WIRING_ANNOTATIONS.any { name.isInPackage(it) }

private fun wiringIn(file: ModuleFile): List<String> {
    val prefix = "ARCH-6 (CLAUDE.md, ADR-005): ${file.path} in ${file.module}"
    val explicitImports =
        file.file.imports
            .filterNot { it.isWildcard }
            .map { it.name }
    val starImports =
        file.file.imports
            .filter { it.isWildcard }
            .map { it.name }

    val imports =
        file.file.imports
            .map { it.name }
            .filter(::isWiring)
            .map { "$prefix imports $it" }
    val annotations =
        file.file
            .declarations(includeNested = true, includeLocal = true)
            .filterIsInstance<KoAnnotationProvider>()
            .flatMap { it.annotations }
            .flatMap { candidateNames(it, starImports) }
            .filter(::isWiring)
            .distinct()
            .map { "$prefix is annotated with @$it" }
    val aliases =
        file.file.typeAliases.flatMap { alias ->
            val target =
                alias.text
                    .substringAfter('=')
                    .substringBefore('<')
                    .trim()
            resolve(target, explicitImports, starImports)
                .filter(::isWiring)
                .map { "$prefix declares typealias ${alias.name}, which aliases $it" }
        }
    return imports + annotations + aliases
}

private fun candidateNames(
    annotation: KoAnnotationDeclaration,
    starImports: List<String>,
): List<String> {
    val resolved = annotation.fullyQualifiedName
    if (resolved != null && '.' in resolved) return listOf(resolved)
    // `@field:Component(...)` -> `Component`; `@org.x.Component` -> `org.x.Component`.
    val written =
        annotation.text
            .removePrefix("@")
            .substringBefore('(')
            .substringAfterLast(':')
            .trim()
    return if ('.' in written) listOf(written) else starImports.map { "$it.$written" }
}

private fun resolve(
    name: String,
    explicitImports: List<String>,
    starImports: List<String>,
): List<String> =
    when {
        '.' in name -> listOf(name)
        else -> explicitImports.filter { it.substringAfterLast('.') == name } + starImports.map { "$it.$name" }
    }

private fun isErrorHandling(name: String): Boolean = ERROR_HANDLING_TYPES.any { name.isInPackage(it) }

private fun ModuleFile.isInAppErrorPackage(modules: List<Module>): Boolean {
    val module = modules.named(module)
    val pkg = file.packagee?.name
    return module?.role == Role.APP && pkg != null && pkg.isInPackage("${module.rootPackage}.error")
}

private fun errorHandlingIn(file: ModuleFile): List<String> {
    val prefix = "ARCH-10 (ADR-015, ADR-FND-04-005): ${file.path} in ${file.module}"
    val starImports =
        file.file.imports
            .filter { it.isWildcard }
            .map { it.name }

    val imports =
        file.file.imports
            .map { it.name }
            .filter(::isErrorHandling)
            .map { "$prefix imports $it" }
    val inline =
        inlineQualifiedNames(file.file.text)
            .filter(::isErrorHandling)
            .sorted()
            .map { "$prefix references $it" }
    val annotationNames =
        file.file
            .declarations(includeNested = true, includeLocal = true)
            .filterIsInstance<KoAnnotationProvider>()
            .flatMap { it.annotations }
            .flatMap { candidateNames(it, starImports) }
            .filter(::isErrorHandling)
            .distinct()
    val annotations = annotationNames.map { "$prefix is annotated with @$it" }
    // Supertypes and other uses of a star-imported name, such as `class X : WebExceptionHandler`.
    val starUses =
        simpleTypeNames(file.file.text)
            .flatMap { name -> starImports.map { "$it.$name" } }
            .filter { isErrorHandling(it) && it !in annotationNames }
            .distinct()
            .sorted()
            .map { "$prefix uses $it through a star import" }
    return imports + inline + annotations + starUses
}
