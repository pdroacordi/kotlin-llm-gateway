plugins {
    id("llmgw.spring-app")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":gateway-providers"))
    implementation(project(":gateway-infra"))
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.spring.boot.starter.r2dbc)
    implementation(libs.spring.boot.starter.flyway)
    runtimeOnly(libs.r2dbc.pool)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(testFixtures(project(":gateway-core")))
    testImplementation(testFixtures(project))
    testImplementation(libs.r2dbc.pool)
    testImplementation(libs.kotlinx.coroutines.test)
    testFixturesImplementation(platform(libs.spring.boot.dependencies))
    testFixturesImplementation(libs.spring.boot.starter.webflux)
    "integrationTestImplementation"(project(":test-support"))
    "integrationTestImplementation"(libs.openai.java)
    "integrationTestImplementation"(libs.awaitility)
    "mockProvider"(project(":mock-provider"))
}

kover {
    currentProject {
        sources {
            excludedSourceSets.add("testFixtures")
        }
    }
}

// The embedding budget is a quarter of the heap (ADR-GW-05-011): at Gradle's 512 MiB default a call priced at the
// default max-response-size would be refused, and these suites prove the route at its defaults.
tasks.named<Test>("integrationTest") {
    maxHeapSize = "1g"
}

tasks.named("check") {
    dependsOn("detektTestFixtures")
}
