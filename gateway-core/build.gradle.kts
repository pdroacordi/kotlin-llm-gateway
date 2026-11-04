plugins {
    id("llmgw.kotlin-core")
    `java-test-fixtures`
}

// Plain Kotlin fixtures for the team store, shared with the adapters' and the app's tests (ADR-GW-04-010).
dependencies {
    testFixturesImplementation(platform(libs.kotlin.bom))
    testFixturesImplementation(platform(libs.kotlinx.coroutines.bom))
    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.junit.jupiter)
    testImplementation(testFixtures(project))
}

kover {
    currentProject {
        sources {
            excludedSourceSets.add("testFixtures")
        }
    }
}

tasks.named("check") {
    dependsOn("detektTestFixtures")
}
