plugins {
    id("llmgw.kotlin-jvm")
}

dependencies {
    testImplementation(libs.konsist)
}

// The rules read every module's sources, so those are inputs of this task: it reruns when any module
// changes and stays cacheable (Konsist docs, "Isolate Konsist tests", offer upToDateWhen { false } instead).
// Must equal what Konsist.scopeFromProject() reads: every .kt file outside build directories, at any depth
// (docs/knowledge/bugs/architecture-test-inputs-miss-nested-module-sources.md). Konsist ignores .kts.
val repositoryRoot = isolated.rootProject.projectDirectory

tasks.test {
    inputs
        .files(
            repositoryRoot.asFileTree.matching {
                include("**/*.kt")
                exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**")
            },
        ).withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("repositoryKotlinSources")
}
