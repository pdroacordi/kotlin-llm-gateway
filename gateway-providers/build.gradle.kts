plugins {
    id("llmgw.kotlin-jvm")
}

dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation(project(":gateway-core"))
    implementation(libs.spring.webflux)
    // ReactorClientHttpConnector implements SmartLifecycle; spring-webflux declares spring-context optional.
    implementation(libs.spring.context)
    implementation(libs.reactor.netty.http)
    implementation(libs.jackson.databind)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.slf4j.api)
    testImplementation(libs.blockhound.junit.platform)
    testImplementation(libs.kotlinx.coroutines.test)
    "integrationTestImplementation"(project(":test-support"))
    "mockProvider"(project(":mock-provider"))
}

// BlockHound on JDK 25 (FND-01 U8); llmgw.spring-app does the same for app modules.
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}
