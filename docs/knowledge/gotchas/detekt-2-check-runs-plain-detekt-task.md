---
id: gotchas/detekt-2-check-runs-plain-detekt-task
type: gotcha
title: detekt 2.0 wires check to the plain detekt task, which has no type resolution
status: active
tags: [detekt, gradle, static-analysis]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/src/main/kotlin/llmgw.kotlin-jvm.gradle.kts
---

## Summary

In `dev.detekt` 2.0.0-alpha.6 the Gradle plugin makes `check` depend on the plain `detekt` task, not on `detektMain`/`detektTest`. Rules that need type resolution (most coroutine rules: `InjectDispatcher`, `SuspendFunSwallowedCancellation`, `RedundantSuspendModifier`) do nothing there, and `build` analyses the sources twice once the typed tasks are added.

## Details

`dev.detekt.gradle.internal.DetektPlain.registerDetektTask` (detekt-gradle-plugin 2.0.0-alpha.6, read with `javap`) registers `detekt` and runs `tasks.matching { it.name == "check" }.configureEach { dependsOn(detektTaskProvider) }`. The typed tasks are registered per source set by `DetektBasePlugin.registerSourceSetTasks`, and nothing attaches them to `check`.

## How to handle it

`llmgw.kotlin-jvm.gradle.kts:64-67` removes the plain task from `check`'s dependencies and adds the typed ones:

```kotlin
tasks.named("check") {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "detekt" })
    dependsOn("detektMain", "detektTest", "koverVerify")
}
```

This only works if detekt's `configureEach` action runs first, which it does when `dev.detekt` comes earlier in the `plugins {}` block. `./gradlew build --dry-run` must not list `:<module>:detekt`. Recheck on every detekt upgrade: the wiring may change before 2.0 GA.
