plugins {
    id("llmgw.spring-app")
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(project(":gateway-providers"))
    implementation(libs.kotlinx.coroutines.slf4j)
    "integrationTestImplementation"(project(":test-support"))
    "integrationTestImplementation"(libs.openai.java)
    "mockProvider"(project(":mock-provider"))
}
