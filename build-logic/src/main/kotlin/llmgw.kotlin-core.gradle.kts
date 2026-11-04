import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("llmgw.kotlin-jvm")
}

// Nothing from Spring, Reactor or any I/O library: core is plain Kotlin (CLAUDE.md, ADR-005).
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    implementation(lib("kotlinx-coroutines-core"))
    testImplementation(lib("mockk"))
    testImplementation(lib("kotlinx-coroutines-test"))
}

kover {
    reports {
        verify {
            rule("core line coverage") {
                minBound(80, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

// Konsist cannot see a dependency added to a core build script (it reads no .kts), so the resolved
// classpath is checked here (docs/knowledge/gotchas/konsist-does-not-scan-kts-files.md).
val checkCoreClasspath =
    tasks.register("checkCoreClasspath") {
        description = "Fails when a core module's main classpath holds a library that core must not use."
        group = "verification"
        // "group" or "group:module-prefix"; a group matches its sub-groups.
        val forbidden =
            listOf(
                "org.springframework",
                "io.projectreactor",
                "io.netty",
                "io.r2dbc",
                "io.lettuce",
                "redis.clients",
                "org.apache.kafka",
                "io.opentelemetry:opentelemetry-sdk",
                "io.opentelemetry:opentelemetry-exporter",
                "org.jetbrains.kotlinx:kotlinx-coroutines-reactor",
                "org.jetbrains.kotlinx:kotlinx-coroutines-reactive",
                "org.jooq",
                "com.zaxxer",
                "org.postgresql",
                "com.mysql",
                "org.mariadb.jdbc",
                "com.h2database",
                "org.xerial",
                "com.squareup.okhttp3",
                "io.ktor",
                "org.apache.httpcomponents",
                "tools.jackson",
                "com.fasterxml.jackson",
                "org.jetbrains.kotlinx:kotlinx-serialization",
                "com.openai",
                "com.anthropic",
            )
        val modules =
            listOf("compileClasspath", "runtimeClasspath").map { name ->
                configurations.named(name).flatMap { configuration ->
                    configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                        artifacts
                            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                            .map { "${it.group}:${it.module}" }
                    }
                }
            }
        val resolved = objects.listProperty<String>()
        modules.forEach { resolved.addAll(it) }
        inputs.property("resolvedModules", resolved)
        doLast {
            val offending =
                resolved.get().distinct().sorted().filter { coordinates ->
                    val group = coordinates.substringBefore(':')
                    forbidden.any { rule ->
                        if (':' in rule) {
                            coordinates.startsWith(rule)
                        } else {
                            group == rule || group.startsWith("$rule.")
                        }
                    }
                }
            check(offending.isEmpty()) {
                "Core modules are plain Kotlin (CLAUDE.md, ADR-005); remove from ${path.substringBeforeLast(':')}: " +
                    offending.joinToString()
            }
        }
    }

tasks.named("check") {
    dependsOn(checkCoreClasspath)
}
