plugins {
    id("llmgw.spring-app")
}

dependencies {
    // Installs BlockHound in every test JVM of this module (ADR-FND-03-009).
    testImplementation(libs.blockhound.junit.platform)
    // Docker-backed tests only (ADR-016).
    "integrationTestImplementation"(platform(libs.testcontainers.bom))
    "integrationTestImplementation"(libs.testcontainers.core)
}

tasks.bootJar {
    archiveFileName = "mock-provider.jar"
}

// Consumers build a Testcontainers image from this jar and mock-provider/Dockerfile (ADR-FND-03-002). Variant-aware
// sharing (Gradle "share outputs between projects"): a consumer resolves this attribute, never this project's tasks.
val mockProviderBootJar =
    configurations.consumable("mockProviderBootJar") {
        attributes {
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named("mock-provider-boot-jar"))
        }
    }

artifacts {
    add(mockProviderBootJar.name, tasks.bootJar)
}

// BlockHound on JDK 25 (FND-01 U8).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}

tasks.named<Test>("integrationTest") {
    // A local provider: the argument lambda must not capture the script object (configuration cache).
    val bootJarFile = tasks.bootJar.flatMap { it.archiveFile }
    inputs
        .file(bootJarFile)
        .withPathSensitivity(PathSensitivity.NONE)
        .withPropertyName("mockProviderBootJar")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider { listOf("-DmockProvider.jar=${bootJarFile.get().asFile.absolutePath}") },
    )
}
