import dev.detekt.gradle.Detekt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
    id("org.jetbrains.kotlinx.kover")
}

// Precompiled script plugins cannot use the generated `libs` accessors (Gradle version catalog docs).
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

val javaVersion = 25

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(platform(lib("kotlin-bom")))
    implementation(platform(lib("kotlinx-coroutines-bom")))
    testImplementation(platform(lib("junit-bom")))
    testImplementation(lib("junit-jupiter"))
    testRuntimeOnly(lib("junit-platform-launcher"))
}

tasks.test {
    useJUnitPlatform {
        excludeTags("live")
    }
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

ktlint {
    version = libs.findVersion("ktlint").get().requiredVersion
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootDir.resolve("config/detekt/detekt.yml"))
}

// Type resolution reads the JDK from jdkHome; without it detekt uses the daemon's JDK (21 locally).
val toolchainHome =
    javaToolchains
        .launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        .map { it.metadata.installationPath }

tasks.withType<Detekt>().configureEach {
    jdkHome = toolchainHome
    jvmTarget = javaVersion.toString()
}

// `detektMain` and `detektTest` replace the plain `detekt` task: they have type resolution, it has not.
tasks.named("check") {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
    dependsOn("detektMain", "detektTest", "koverVerify")
}
