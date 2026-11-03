---
id: bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations
type: bug
title: ARCH-6 lets a Spring wiring annotation through when it is written fully qualified or through a typealias
status: active
severity: medium
tags: [konsist, architecture, spring, wiring]
related: [gotchas/konsist-annotation-fqn-unresolved-for-qualified-star-typealias, patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/RuleFixturesTest.kt
---

## Summary

ARCH-6 ("wiring annotations only in app modules") matches `KoAnnotationDeclaration.fullyQualifiedName` against the wiring list. Konsist resolves that name only through an explicit import, so `@org.springframework.stereotype.Component`, `@Service` under `import org.springframework.stereotype.*`, and `@Wiring` with `typealias Wiring = org.springframework.stereotype.Service` all pass in a non-app module.

## Symptom

Found in review of FND-01 with Konsist 0.18.1 on Windows and Linux (scratch fixtures under a `gateway-infra/src/main/kotlin/...` layout, run through `wiringOnlyInApp`):

| Written as | `fullyQualifiedName` | ARCH-6 |
|---|---|---|
| `import ...Component as Wiring` + `@Wiring` | `org.springframework.stereotype.Component` | flagged |
| `@Bean` on a member, companion or object function | `org.springframework.context.annotation.Bean` | flagged |
| `@org.springframework.stereotype.Component` | `Component` | missed |
| `import org.springframework.stereotype.*` + `@Service` | `Service` | missed (ktlint `no-wildcard-imports` blocks this in practice) |
| `typealias Bean2 = org.springframework.stereotype.Service` + `@Bean2` | `...gateway.infra.Bean2` | missed |

Adapters (`gateway-infra`, `gateway-providers`) will have Spring libraries on their classpath, so the Gradle classpath does not stop this: ARCH-6 is the only guard there.

## Root cause

`ArchitectureRules.kt:131-132`: `.mapNotNull { it.fullyQualifiedName }.filter { name -> WIRING_ANNOTATIONS.any { name.isInPackage(it) } }`. An unresolved name has no package, so it never matches.

## Fix

Fixed in FND-01, in `architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt`:

- `wiringOnlyInApp` (`ArchitectureRules.kt:144-150`) now hands every file outside role app to `wiringIn` (`:256-293`).
- `wiringIn` flags any import, explicit or star, whose name is a wiring annotation or package. It flags annotations by their candidate names. It also flags any typealias whose target resolves to a wiring annotation (`resolve`, `:311-319`, against explicit and star imports).
- `candidateNames` (`:295-309`) keeps Konsist's `fullyQualifiedName` when it has a dot. Otherwise it reads the annotation text without `@`, use-site target and arguments. Text with a dot is the full name; text without one is resolved against the file's star imports.

The Root cause lines refer to the file before the fix. Commit: the FND-01 commit (`Roadmap: FND-01`).

## Prevention

Added. `RuleFixturesTest` has a fixture per spelling. `arch-6-fully-qualified` covers `@org.springframework.stereotype.Component`. `arch-6-typealias` covers `typealias Wiring = org.springframework.stereotype.Service`. `arch-6-star-import` covers `@Configuration` under `context.annotation.*` and `@Service` under `stereotype.*`. The existing `arch-6-component-outside-app` now also asserts the import message. Each failed before the fix (red run: 30 tests, 7 failed) and passes after it. The general rule: the fixture pattern only proves the rule fires on the spellings in its fixtures. Each rule needs a fixture per spelling of the violation: import, alias, star, fully qualified, typealias.
