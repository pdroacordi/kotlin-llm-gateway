import org.gradle.api.tasks.testing.logging.TestExceptionFormat

// ADR-016: tests that need Docker live in `src/integrationTest` and run in the `integrationTest` suite, which
// `check` (so `build` and CI) depends on; `test` stays Docker-free. Applied by `llmgw.kotlin-jvm`, so every module
// has the suite; an empty one is NO-SOURCE. Kover instruments every `Test` task, so it measures both suites.
plugins {
    java
    `jvm-test-suite`
    id("org.jetbrains.kotlinx.kover")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)
            dependencies {
                implementation(project())
            }
            targets.configureEach {
                testTask.configure {
                    shouldRunAfter(tasks.named("test"))
                    (options as JUnitPlatformOptions).excludeTags("live")
                    testLogging {
                        events("failed")
                        exceptionFormat = TestExceptionFormat.FULL
                    }
                }
            }
        }
    }
}

// Same libraries as the unit suite (JUnit, MockK, Spring test, BlockHound where a module adds it).
configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.getByName("testImplementation"))
}
configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.getByName("testRuntimeOnly"))
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

// Consumer side of the mock-provider image (ADR-FND-02-010, ADR-FND-03-002). A module that declares
// `mockProvider(project(":mock-provider"))` gets the boot jar and mock-provider/Dockerfile as inputs and as the
// system properties mockProvider.jar and mockProvider.dockerfile in its integrationTest JVM; any other module gets
// neither. The jar comes from the variant attribute, so Gradle builds it through the artifact, never by naming
// another project's task.
val mockProvider = configurations.dependencyScope("mockProvider")
val mockProviderJar =
    configurations.resolvable("mockProviderJar") {
        extendsFrom(mockProvider.get())
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named("mock-provider-boot-jar"))
        }
    }

tasks.named<Test>("integrationTest") {
    // Locals only: the argument lambda must not capture the script object (configuration cache).
    val jars: FileCollection = files(mockProviderJar)
    val dockerfile =
        isolated.rootProject.projectDirectory
            .file("mock-provider/Dockerfile")
            .asFile
    val dockerfileWhenUsed = jars.elements.map { if (it.isEmpty()) emptyList() else listOf(dockerfile) }
    inputs
        .files(jars)
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("mockProviderJar")
    inputs
        .files(dockerfileWhenUsed)
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("mockProviderDockerfile")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val jar = jars.files.singleOrNull()
            if (jar == null) {
                emptyList()
            } else {
                listOf("-DmockProvider.jar=${jar.absolutePath}", "-DmockProvider.dockerfile=${dockerfile.absolutePath}")
            }
        },
    )
}

// Kover reports every non-`test` compilation as application code; the suite's own classes are tests. Its test task
// stays instrumented, so what it exercises in `main` still counts.
kover {
    currentProject {
        sources {
            excludedSourceSets.add("integrationTest")
        }
    }
}
