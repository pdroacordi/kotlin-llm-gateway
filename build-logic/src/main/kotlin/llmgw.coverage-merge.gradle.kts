import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

// Root project only: merges the coverage of the modules listed as `kover(project(...))` in the root build.
plugins {
    base
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion
}

// The app entry points to exclude from the merged report are named in the root build.
kover {
    reports {
        verify {
            rule("overall line coverage") {
                minBound(70, CoverageUnit.LINE, AggregationType.COVERED_PERCENTAGE)
            }
        }
    }
}

// Task selectors do not reach included builds (docs/knowledge/gotchas/gradle-task-selectors-skip-included-builds.md),
// so the root tasks pull in build-logic's: the convention plugins are linted, validated and formatted too.
val buildLogic = gradle.includedBuild("build-logic")

tasks.named("check") {
    dependsOn("koverVerify")
    dependsOn(buildLogic.task(":check"))
}

tasks.named("ktlintCheck") {
    dependsOn(buildLogic.task(":ktlintCheck"))
}

tasks.named("ktlintFormat") {
    dependsOn(buildLogic.task(":ktlintFormat"))
}
