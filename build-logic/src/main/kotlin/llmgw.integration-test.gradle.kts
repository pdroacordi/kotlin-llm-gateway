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

// Kover reports every non-`test` compilation as application code; the suite's own classes are tests. Its test task
// stays instrumented, so what it exercises in `main` still counts.
kover {
    currentProject {
        sources {
            excludedSourceSets.add("integrationTest")
        }
    }
}
