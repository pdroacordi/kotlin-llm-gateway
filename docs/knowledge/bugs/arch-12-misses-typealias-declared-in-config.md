---
id: bugs/arch-12-misses-typealias-declared-in-config
type: bug
title: ARCH-12 passes a handler that uses the adapter through a typealias declared in the config package
status: active
severity: low
tags: [architecture, konsist]
related: [systems/provider-adapters, bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations, patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

`typealias Adapter = OpenAiChatAdapter` under `<app root>.config` is allowed there, and `import ...app.config.Adapter` in `.chat` names nothing under the adapter's root, so a handler can call the adapter past the port with ARCH-12 green. ARCH-6 had this class of hole and closed it.

## Symptom

Found in the GW-01 review by reading the rule. Imports, inline names and star imports of the adapter's package are all caught; the fixture has no alias case. Kotlin's own `internal` still stops wire code, so only the public `OpenAiChatAdapter`, `OpenAiAdapterConfig` and `PoolConfig` are exposed this way.

## Root cause

`adaptersReferencedOnlyFromWiring` (`ArchitectureRules.kt:289`) skips files in `.config` and looks at names in the others; it never asks what a `.config` declaration exports.

## Fix

`adaptersReferencedOnlyFromWiring` also flags a non-private typealias in the wiring package whose target is under an adapter root (`exportedAdapterAliases`, `ArchitectureRules.kt:430`); `aliasTargets` (`:445`) is shared with ARCH-6. A `private` alias stays allowed, since nothing can import it.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

Fixture `arch-12-adapter-outside-wiring`: `AdapterAlias.kt` (an imported and a fully qualified target, and a private alias) and `AliasedHandler.kt`; `RuleFixturesTest` was red first. Review rule, already learned from ARCH-6 and ARCH-10: a new name rule gets a typealias fixture on the day it is written.
