plugins {
    id("llmgw.spring-app")
}

dependencies {
    implementation(project(":gateway-core"))
    implementation(libs.kotlinx.coroutines.slf4j)
}
