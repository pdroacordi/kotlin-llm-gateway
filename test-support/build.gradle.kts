// Testcontainers fixtures for every module's integrationTest suite (FND-02, ADR-017, ADR-FND-02-006).
// No production code: not in the root Kover merge.
plugins {
    id("llmgw.kotlin-jvm")
    `java-library`
}

dependencies {
    // Aligns Testcontainers, the Postgres driver, kafka-clients and SnakeYAML with what the apps get.
    api(platform(libs.spring.boot.dependencies))
    // Container types are public API, so Spring tests can put @ServiceConnection on them.
    api(libs.testcontainers.core)
    api(libs.testcontainers.postgresql)
    api(libs.testcontainers.redpanda)
    // Loaded by BlockHound where a suite has it; a suite without it never touches this class.
    compileOnly(libs.blockhound)
    // PostgreSQLContainer checks readiness over JDBC.
    runtimeOnly(libs.postgresql)
    testImplementation(libs.snakeyaml)
    "integrationTestImplementation"(libs.kafka.clients)
    "mockProvider"(project(":mock-provider"))
}

tasks.test {
    // StackContractTest reads deploy/compose.yaml; an edit there reruns it.
    inputs
        .file(isolated.rootProject.projectDirectory.file("deploy/compose.yaml"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("composeFile")
}
