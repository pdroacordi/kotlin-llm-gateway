plugins {
    id("llmgw.kotlin-jvm")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":gateway-core"))
    implementation(libs.spring.r2dbc)
    implementation(libs.r2dbc.postgresql)
    implementation(libs.caffeine)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.slf4j.api)
    testImplementation(testFixtures(project(":gateway-core")))
    testImplementation(libs.blockhound.junit.platform)
    testImplementation(libs.kotlinx.coroutines.test)
    "integrationTestImplementation"(project(":test-support"))
    "integrationTestImplementation"(libs.r2dbc.pool)
    "integrationTestImplementation"(libs.flyway.core)
    "integrationTestImplementation"(libs.awaitility)
    "integrationTestRuntimeOnly"(libs.flyway.database.postgresql)
}

// BlockHound on JDK 25 (FND-01 U8); llmgw.spring-app does the same for app modules.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}
