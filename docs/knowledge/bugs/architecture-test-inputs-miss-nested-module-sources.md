---
id: bugs/architecture-test-inputs-miss-nested-module-sources
type: bug
title: Kotlin added under an existing top-level directory leaves the architecture tests UP-TO-DATE, so ARCH-8 does not run
status: active
severity: medium
tags: [build, gradle, konsist, architecture, build-cache]
related: [patterns/architecture-rule-with-violating-fixture, gotchas/konsist-paths-and-module-names-use-platform-separator]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/build.gradle.kts
---

## Summary

`:architecture-tests:test` declares the repository's Kotlin sources as inputs with the pattern `*/src/**/*.kt`. That pattern matches only modules one level below the root. A nested module (`tools/load/src/main/kotlin/X.kt`) or a root `src/` is scanned by Konsist but is not an input, so the task stays UP-TO-DATE or loads FROM-CACHE and the ARCH-8 violation never surfaces.

## Symptom

Found in review of FND-01, reproduced on a copy of the tree (2026-11-03):

```
./gradlew :architecture-tests:test          # FROM-CACHE
mkdir -p scripts/sub/src/main/kotlin && echo 'package x' > scripts/sub/src/main/kotlin/A.kt
./gradlew :architecture-tests:test          # UP-TO-DATE, BUILD SUCCESSFUL
./gradlew :architecture-tests:test --rerun  # FAILED: ARCH-8 ... module scripts\sub holds Kotlin sources
```

A *new* top-level directory (`tools/`) does change the input snapshot, because Gradle records the directory entry that matched `*`. That hides the bug in a quick test: it shows only when the top-level directory already exists (`scripts/`, `deploy/`, `tools/` once smoke scripts land).

## Root cause

`architecture-tests/build.gradle.kts:17`: `include("*/src/**/*.kt", "*/src/**/*.kts", "*.kts")`. Ant `*` matches one path segment, so the input set differs from what `Konsist.scopeFromProject()` reads at test time (every `.kt` under the root outside build directories). The build cache (`org.gradle.caching=true`, also restored by `setup-gradle` in CI) serves the old result for the same key.

## Fix

Fixed in FND-01. `architecture-tests/build.gradle.kts:19-20`: the inputs are now `include("**/*.kt")` with `exclude("**/build/**", "**/.gradle/**", "**/.kotlin/**", ".git/**")`, the same scope `Konsist.scopeFromProject()` reads. The `.kts` patterns are gone: Konsist does not read `.kts` ([gotchas/konsist-does-not-scan-kts-files](../gotchas/konsist-does-not-scan-kts-files.md)). The comment at `architecture-tests/build.gradle.kts:9-12` states the invariant and links this entry. The Root cause line refers to the file before the fix. Commit: the FND-01 commit (`Roadmap: FND-01`).

## Prevention

Added for FND-01: a deliberate violation with a nested module under an existing directory, `scripts/sub/src/main/kotlin/A.kt`. Before the fix the task stayed UP-TO-DATE. After it the task reran and ARCH-8 reported `module scripts/sub`; once the file was removed it came back FROM-CACHE. Not a committed fixture: the bug is in the task's inputs, which a test that reads its own scope cannot see. The committed guard is the new unit test `ModuleClassificationTest` "a nested module name uses forward slashes on every platform", which keeps nested module names comparable across Windows and Linux. Review checklist: declared inputs must equal the scope the test reads.
