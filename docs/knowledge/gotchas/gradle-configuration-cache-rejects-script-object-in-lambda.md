---
id: gotchas/gradle-configuration-cache-rejects-script-object-in-lambda
type: gotcha
title: A task lambda (CommandLineArgumentProvider, doLast) that reads a top-level val of a .gradle.kts script captures the script object and breaks the configuration cache
status: active
tags: [gradle, configuration-cache, kotlin-dsl]
related: [gotchas/ktlint-gradle-lints-kotlin-dsl-generated-sources]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/build.gradle.kts
---

## Summary

A top-level `val` in a Kotlin build script is a property of the script class. A lambda that reads it holds a reference to the script instance, and the configuration cache cannot serialize that. With `org.gradle.configuration-cache=true` (this repository's `gradle.properties`), the build fails.

## Details

Reproduced on 2026-11-03 with this repository's wrapper and a scratch project:

```kotlin
plugins { java }
val marker = layout.buildDirectory.file("m.txt")
tasks.named<Test>("test") {
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dm=${marker.get().asFile}") })
}
```

`./gradlew test --configuration-cache` fails with: "Task `:test` of type `org.gradle.api.tasks.testing.Test`: cannot serialize Gradle script object references as these are not supported with the configuration cache." Gradle documents script objects among the types the configuration cache rejects (configuration cache requirements, "Disallowed types").

## How to handle it

Copy the value into a local `val` inside the configuration block, and capture only that local: `mock-provider/build.gradle.kts:37-44` (`val bootJarFile = tasks.bootJar.flatMap { it.archiveFile }` inside `tasks.named<Test>("integrationTest") { ... }`). Declare the file as a task input too (`inputs.file(...)`), so the jar is built first and changes rerun the task.
