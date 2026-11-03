---
id: gotchas/konsist-does-not-scan-kts-files
type: gotcha
title: Konsist scopes contain only .kt files; build scripts and other .kts are never checked
status: active
tags: [konsist, architecture, gradle]
related: [bugs/architecture-test-inputs-miss-nested-module-sources, bugs/arch-1-misses-jdk-clients-in-core]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/build.gradle.kts
  - build-logic/src/main/kotlin/llmgw.kotlin-core.gradle.kts
---

## Summary

`Konsist.scopeFromProject()` and `scopeFromDirectory(...)` (0.18.1) list `.kt` files only. A `build.gradle.kts` or any other `.kts` in the same directory is absent from the scope, on Windows and Linux.

## Details

Observed 2026-11-03: a scratch directory held `S.kt` and `Script.kts`, and `gateway-core/build.gradle.kts` held `import org.springframework.stereotype.Component`. Only `S.kt` appeared in the scope, and no rule saw the `.kts` import.

## How to handle it

- No Konsist rule can catch a forbidden dependency being *added* to a module's build script. Guard it in Gradle instead: here the `checkCoreClasspath` task in `build-logic/src/main/kotlin/llmgw.kotlin-core.gradle.kts:32-95` fails `check` when a core module's resolved compile or runtime classpath contains a forbidden group.
- Do not declare `.kts` files as inputs of the Konsist test task to make it "see" build changes: they only cause reruns. `architecture-tests/build.gradle.kts:19-20` declares `**/*.kt` only.
