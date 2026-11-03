plugins {
    `kotlin-dsl`
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
}

ktlint {
    version = libs.versions.ktlint.asProvider()
    filter {
        // kotlin-dsl adds generated accessors and plugin adapters to the main source set.
        // A local val keeps the spec free of script references (configuration cache).
        val generated =
            layout.buildDirectory
                .get()
                .asFile
                .toPath()
        exclude { it.file.toPath().startsWith(generated) }
    }
}
