plugins {
    id("llmgw.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    // A Gradle platform, not io.spring.dependency-management (ADR-FND-01-004): versions in the
    // catalog must be >= Boot's, since a platform only recommends and the highest version wins.
    implementation(platform(lib("spring-boot-dependencies")))
    implementation(lib("spring-boot-starter-webflux"))
    implementation(lib("spring-boot-starter-actuator"))
    implementation(lib("kotlin-reflect"))
    implementation(lib("kotlinx-coroutines-reactor"))
    testImplementation(lib("spring-boot-starter-webflux-test"))
    // Installs BlockHound in every test JVM of an app module (ADR-FND-03-009).
    testImplementation(lib("blockhound-junit-platform"))
}

// BlockHound on JDK 25 (FND-01 U8).
tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
}

kover {
    reports {
        filters {
            excludes {
                // The `main` entry point runs under bootRun, not under tests.
                classes("*ApplicationKt")
            }
        }
    }
}
