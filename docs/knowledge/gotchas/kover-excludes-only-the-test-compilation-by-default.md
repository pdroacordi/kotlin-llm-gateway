---
id: gotchas/kover-excludes-only-the-test-compilation-by-default
type: gotcha
title: Kover 0.9 treats every JVM compilation except the one named `test` as application code, so an integrationTest suite's classes join the coverage report
status: active
tags: [kover, coverage, gradle, testing]
related: [gotchas/detekt-default-test-excludes-miss-integration-test]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts
---

## Summary

In Kover 0.9.11, a JVM compilation is left out of the report only if it is in `sources.excludedSourceSets`, or if `includedSourceSets` is empty and its name is exactly `test`. The classes of a `jvm-test-suite` named `integrationTest` are measured as production code unless excluded.

## Details

`javap -c` on `kover-gradle-plugin-0.9.11.jar`, `JvmVariantArtifacts.compilationIsExcluded(name, sources)`: it returns true if `excludedSourceSets.contains(name)`; otherwise true if `includedSourceSets.isEmpty() && name == "test"`; otherwise true if `includedSourceSets` is non-empty and does not contain `name`. Adding a name to `excludedSourceSets` does not disable the `test` default.

## How to handle it

`kover { currentProject { sources { excludedSourceSets.add("integrationTest") } } }` (`build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts:49-55`). Leave `includedSourceSets` empty, or the `test` default stops applying. The suite's `Test` task stays instrumented, so what it exercises in `main` still counts.
