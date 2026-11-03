---
id: gotchas/konsist-paths-and-module-names-use-platform-separator
type: gotcha
title: Konsist projectPath and moduleName use backslashes on Windows, slashes on Linux
status: active
tags: [konsist, windows, architecture, ci]
related: [bugs/architecture-test-inputs-miss-nested-module-sources]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

Konsist 0.18.1 builds `KoFileDeclaration.projectPath` and `moduleName` with the platform separator. For a top-level module the module name has no separator, so it is the same everywhere. For a nested module it differs between a Windows workstation and Linux CI.

## Details

Observed 2026-11-03, same tree on both platforms:

| | Windows | Linux |
|---|---|---|
| `projectPath` | `\fx\nested\tools\smoke\src\main\kotlin\X.kt` | `/fx/nested/tools/smoke/src/main/kotlin/X.kt` |
| `moduleName` | `fx\nested\tools\smoke` | `fx/nested/tools/smoke` |
| `sourceSetName` | `main` | `main` |
| `moduleName` of files directly under the root's `src/` | `root` | `root` |

`moduleName` is the path before the first `src` segment, and `sourceSetName` is the segment after it. So a fixture at `architecture-tests/src/fixtures/kotlin/<case>/gateway-core/src/main/...` gets module `architecture-tests` and source set `fixtures` on both platforms.

## How to handle it

Normalize with `replace('\\', '/')` before comparing or printing: `ModuleFile.path` does this at `ArchitectureRules.kt:15`. `ModuleFile.module` (`ArchitectureRules.kt:168`) is not normalized yet. That is harmless while every module sits at the top level, but a nested module added to the table as `tools/smoke` would match on Linux and fail on Windows.

## Revision (2026-11-03)

Fixed in FND-01 after review. Both `ModuleFile` fields are now normalized in its constructor: `module` at `ArchitectureRules.kt:18` and `path` at `ArchitectureRules.kt:19`, which also drops the leading `/`. The line numbers above refer to the file before the fix. `ModuleClassificationTest` "a nested module name uses forward slashes on every platform" asserts that `tools\smoke` becomes `tools/smoke`. A deliberate violation under `scripts/sub/` printed `module scripts/sub` on Windows. The rule to remember: compare and print through `ModuleFile`, never through Konsist's raw `moduleName` or `projectPath`.
