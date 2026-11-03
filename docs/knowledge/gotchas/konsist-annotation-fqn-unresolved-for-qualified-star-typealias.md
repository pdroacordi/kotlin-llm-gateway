---
id: gotchas/konsist-annotation-fqn-unresolved-for-qualified-star-typealias
type: gotcha
title: Konsist resolves an annotation's fullyQualifiedName only through an explicit import
status: active
tags: [konsist, architecture, annotations]
related: [bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

In Konsist 0.18.1, `KoAnnotationDeclaration.fullyQualifiedName` is resolved from the file's explicit imports, aliases included. An annotation written fully qualified, imported with a star, or reached through a typealias comes back unqualified or with the wrong package. Same result on Windows and Linux.

## Details

Observed 2026-11-03 with scratch files in package `...gateway.infra`:

| Source | `name` | `fullyQualifiedName` |
|---|---|---|
| `import org.springframework.stereotype.Component as Wiring`, `@Wiring` | `Wiring` | `org.springframework.stereotype.Component` |
| `@org.springframework.stereotype.Component` (no import) | `Component` | `Component` |
| `import org.springframework.stereotype.*`, `@Service` | `Service` | `Service` |
| `typealias Bean2 = org.springframework.stereotype.Service`, `@Bean2` | `Bean2` | `io.github.pdroacordi.llmgateway.gateway.infra.Bean2` |

Konsist parses source without type resolution (PSI only), so it cannot follow a star import or a typealias.

## How to handle it

A rule that matches annotations by `fullyQualifiedName` must also handle the other spellings: fall back to `annotation.text` when the name has no dot, resolve the name against the file's wildcard imports, and inspect `typeAliases`. Give each spelling its own violating fixture ([patterns/architecture-rule-with-violating-fixture](../patterns/architecture-rule-with-violating-fixture.md)). Today's rule: `wiringOnlyInApp` at `ArchitectureRules.kt:144-150`, with `wiringIn` (`:256-293`) and `candidateNames` (`:295-309`); fixtures `arch-6-fully-qualified`, `arch-6-star-import`, `arch-6-typealias` ([bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations](../bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations.md)).
