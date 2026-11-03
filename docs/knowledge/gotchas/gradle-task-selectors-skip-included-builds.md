---
id: gotchas/gradle-task-selectors-skip-included-builds
type: gotcha
title: ./gradlew ktlintCheck or ktlintFormat from the root does not run in the included build-logic
status: active
tags: [gradle, included-build, ktlint, build-logic]
related: [gotchas/ktlint-gradle-lints-kotlin-dsl-generated-sources]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/src/main/kotlin/llmgw.coverage-merge.gradle.kts
  - CLAUDE.md
---

## Summary

A task name given without a path (`./gradlew ktlintFormat`) selects that task in every project of the *main* build only. Included builds such as `build-logic` are skipped. Their tasks run only when something depends on them explicitly, or when they are addressed by path (`./gradlew :build-logic:ktlintFormat`).

## Details

Checked with Gradle 9.7.1 on 2026-11-03: `./gradlew ktlintFormat --dry-run` lists the root, `architecture-tests`, `gateway-app` and `gateway-core` format tasks and no `:build-logic:*` ktlint task. The same holds for `./gradlew ktlintCheck detektMain detektTest`. `./gradlew build` does lint `build-logic`, because the root `check` depends on `gradle.includedBuild("build-logic").task(":check")` (`llmgw.coverage-merge.gradle.kts:32-35`).

## How to handle it

- A style failure in a convention plugin shows up only in `./gradlew build`. Fix it with `./gradlew :build-logic:ktlintFormat`.
- To make the bare commands cover `build-logic`, have the root `ktlintCheck` and `ktlintFormat` tasks depend on `gradle.includedBuild("build-logic").task(":ktlintCheck")` and `.task(":ktlintFormat")`, the same way `check` already does.

## Revision (2026-11-03)

This repository now does the second bullet (FND-01 review). `llmgw.coverage-merge.gradle.kts:37-43` makes the root `ktlintCheck` and `ktlintFormat` depend on `:build-logic:ktlintCheck` and `:build-logic:ktlintFormat`. `./gradlew ktlintCheck --dry-run` lists the `:build-logic:ktlint*Check` tasks, and `ktlintFormat --dry-run` lists the `:build-logic:ktlint*Format` tasks. The Gradle behaviour above is unchanged. Any other task name run from the root still skips `build-logic` unless it is wired the same way.
