import dev.detekt.gradle.Detekt
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("llmgw.integration-test")
}

// Precompiled script plugins cannot use the generated `libs` accessors (Gradle version catalog docs).
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

val javaVersion = 25

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(platform(lib("kotlin-bom")))
    implementation(platform(lib("kotlinx-coroutines-bom")))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
}

// Type resolution reads the JDK from jdkHome; without it detekt uses the daemon's JDK (21 locally).
val toolchainHome =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        .map { it.metadata.installationPath }

tasks.withType<Detekt>().configureEach {
    jdkHome = toolchainHome
    jvmTarget = javaVersion.toString()
}

// ARCH-11 in the build (ADR-016, ADR-017): Testcontainers and test-support belong on integrationTest classpaths only.
// Konsist reads no .kts (docs/knowledge/gotchas/konsist-does-not-scan-kts-files.md), so a dependency added to a
// build script is caught on the resolved classpaths. test-support itself is exempt: its main is the fixtures, and
// its test classpaths inherit its main's API.
val checkTestSupportClasspath =
    tasks.register("checkTestSupportClasspath") {
        description = "Fails when test-support or Testcontainers is on a main or test classpath."
        group = "verification"
        val guarded =
            if (project.path == ":test-support") {
                emptyList()
            } else {
                listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
            }
        val offending = objects.listProperty<String>()
        guarded.forEach { name ->
            offending.addAll(
                configurations.named(name).flatMap { configuration ->
                    configuration.incoming.artifacts.resolvedArtifacts.map { artifacts ->
                        artifacts.mapNotNull { artifact ->
                            when (val id = artifact.id.componentIdentifier) {
                                is ProjectComponentIdentifier -> {
                                    "$name: project ${id.projectPath}".takeIf { id.projectPath == ":test-support" }
                                }

                                is ModuleComponentIdentifier -> {
                                    "$name: ${id.group}:${id.module}".takeIf { id.group == "org.testcontainers" }
                                }

                                else -> {
                                    null
                                }
                            }
                        }
                    }
                },
            )
        }
        inputs.property("offending", offending)
        doLast {
            val found = offending.get().distinct().sorted()
            check(found.isEmpty()) {
                "test-support and Testcontainers are for integrationTest sources only (ARCH-11, ADR-017); " +
                    "move to integrationTestImplementation in ${path.substringBeforeLast(':')}: " + found.joinToString()
            }
        }
    }

// `detektMain`, `detektTest` and `detektIntegrationTest` replace the plain `detekt` task: they have type
// resolution, it has not.
tasks.named("check") {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
    dependsOn("detektMain", "detektTest", "detektIntegrationTest", "koverVerify", checkTestSupportClasspath)
}
