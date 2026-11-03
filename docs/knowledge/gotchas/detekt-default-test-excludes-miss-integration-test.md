---
id: gotchas/detekt-default-test-excludes-miss-integration-test
type: gotcha
title: detekt's default test excludes list test, androidTest, jvmTest and others, but not integrationTest
status: active
tags: [detekt, gradle, testing]
related: [gotchas/detekt-2-check-runs-plain-detekt-task, gotchas/kover-excludes-only-the-test-compilation-by-default]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - config/detekt/detekt.yml
  - build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts
---

## Summary

In detekt 2.0.0-alpha.6, rules that should not apply to tests carry `excludes: ['**/test/**', '**/androidTest/**', '**/commonTest/**', '**/jvmTest/**', '**/androidUnitTest/**', '**/androidInstrumentedTest/**', '**/jsTest/**', '**/iosTest/**']`. A JVM test suite in `src/integrationTest` (ADR-016) matches none of them, so backtick test names, literals and `!!` in it are findings.

## Details

From `default-detekt-config.yml` in `detekt-core-2.0.0-alpha.6.jar`, the active rules with those excludes are: `TooManyFunctions`, `InstanceOfCheckForException`, `ThrowingExceptionsWithoutMessageOrCause`, `TooGenericExceptionCaught`, `FunctionNaming`, `ForEachOnRange`, `SpreadOperator`, `UnsafeCallOnNullableType`, and `MagicNumber` (which also excludes `**/*.kts`). `UndocumentedPublic*`, `KDocReferencesNonPublicProperty`, `StringLiteralDuplication` and `LateinitUsage` have them too but are inactive by default.

## How to handle it

Override `excludes` for each of those rules with `**/integrationTest/**` added (`config/detekt/detekt.yml:11-13`, `:32-54`). An override replaces the list, so repeat `**/test/**` (and `**/*.kts` for `MagicNumber`). Re-check the list when detekt is upgraded (FND-05).
