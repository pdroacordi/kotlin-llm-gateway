package io.github.pdroacordi.llmgateway.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoAnnotationDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoAnnotationProvider

/**
 * A Kotlin file and the module it belongs to. The module is explicit because Konsist derives it from the
 * path, which is wrong for fixtures (they all sit inside `architecture-tests`).
 */
class ModuleFile(
    module: String,
    val file: KoFileDeclaration,
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

private const val FIXTURES_SOURCE_SET = "fixtures"
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

/** ARCH-9: a module references only its own packages and those of the modules in its `dependsOn`. */
fun importsStayWithinAllowedModules(
    files: List<ModuleFile>,
    modules: List<Module> = Modules.all,
): List<String> =
    files.flatMap { file ->
        val module = modules.named(file.module)
        if (module == null) {
            emptyList()
        } else {
            val allowed = listOf(module.rootPackage) + module.dependsOn.mapNotNull { modules.named(it)?.rootPackage }
            forbiddenReferences(file, "ARCH-9", "ADR-005, ADR-014") { name ->
                name.isInPackage(BASE_PACKAGE) && allowed.none { name.isInPackage(it) }
            }
        }
    }

/** Every Kotlin file of the repository except fixtures, `build-logic/` and generated code under `build/`. */
object ProductionScope {
    val files: List<ModuleFile> by lazy {
        Konsist
            .scopeFromProject()
            .files
            .map { ModuleFile(it.moduleName, it) }
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
fun inlineQualifiedNames(text: String): Set<String> {
    val code =
        text
            .replace(RAW_STRING, "\"\"")
            .replace(BLOCK_COMMENT, " ")
            .replace(STRING, "\"\"")
            .replace(LINE_COMMENT, "")
            .replace(IMPORT_OR_PACKAGE_LINE, "")
    return QUALIFIED_NAME.findAll(code).map { it.value }.toSet()
}

private val RAW_STRING = Regex("\"\"\"[\\s\\S]*?\"\"\"")
private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
private val STRING = Regex(""""(?:\\.|[^"\\\n])*"""")
private val LINE_COMMENT = Regex("//.*")
private val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(import|package)\s.*$""", RegexOption.MULTILINE)
private val QUALIFIED_NAME = Regex("""(?<![\w.])[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+""")

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
