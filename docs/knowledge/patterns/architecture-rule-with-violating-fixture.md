---
id: patterns/architecture-rule-with-violating-fixture
type: pattern
title: Every architecture rule is proven to fire by a violating fixture
status: active
tags: [architecture, konsist, testing, build]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/RuleFixturesTest.kt
  - architecture-tests/src/fixtures/kotlin/
---

## Summary

An architecture rule that checks nothing passes, and so does a rule with a typo in its package prefix or module name. Each Konsist rule is therefore a plain function over a `KoScope`, applied twice: to the real modules (must pass) and to a small fixture directory that breaks it on purpose (must fail).

## Details

- Rules live as functions in `architecture-tests/.../ArchitectureRules.kt`, e.g. a rule that takes a scope and a list of forbidden import prefixes. A rule's failure message names the rule ID (`ARCH-n`), the file, the offending import or annotation, and the ADR it protects.
- Production tests apply each rule to the modules it covers. The modules are discovered from the files Konsist finds (`KoFile.moduleName`), filtered through the module table (ADR-014), never by calling `scopeFromModule` for a module that may not exist yet. So a rule for a module that does not exist yet passes honestly (no files) and starts checking on the module's first file.
- Fixtures live in `architecture-tests/src/fixtures/kotlin/<rule-case>/`. That directory is not a Gradle source set: it is never compiled, linted or analysed by detekt, so a fixture may import Spring from a "core" package without breaking the build. Production scopes exclude the `fixtures` source set.
- `RuleFixturesTest` applies each rule to its fixture scope (`Konsist.scopeFromDirectory(...)`) and asserts that it fails, and that the message names the expected file. A rule whose predicate silently stops matching (renamed package, wrong prefix, wrong annotation name) fails this test.
- A classification test makes sure every module that holds Kotlin files is in the module table, so a new module cannot sit outside every rule.
- The architecture test task declares the Kotlin sources of the whole repository as inputs, so Gradle reruns it when another module changes (Konsist docs, "Isolate Konsist tests", describe the stale up-to-date problem).

## Why

Konsist import assertions on an empty list do not fail by default (only `strict = true` does), and layer assertions throw when a layer has no files, which forces placeholder modules. ArchUnit's user guide (section "Fail Rules on Empty Should") states the same risk: rules "that by accident do not check any classes at all". A fixture per rule is the cheapest way to show the predicate still matches a real violation, the same idea as mutation testing applied to the rule rather than to the code. Sources: https://www.archunit.org/userguide/html/000_Index.html, https://docs.konsist.lemonappdev.com/writing-tests/architecture-assert, https://docs.konsist.lemonappdev.com/advanced/isolate-konsist-tests.

## Gotchas / pitfalls

- A fixture placed under `src/test/kotlin` is compiled and linted: a fixture that imports Spring then breaks the build or needs suppressions. Keep fixtures outside every source set.
- A prefix match must stop at a segment boundary (`name == p || name.startsWith("$p.")`), otherwise `...gateway` also matches `...gatewayx`. Star imports (`org.springframework.*`) must match too.
- Import lists do not show fully qualified references written in code, and the classpath does not stop them for JDK packages (`java.net`, `java.sql` are always there). Match forbidden prefixes in the file text outside imports, comments and strings as well, and guard build-script dependencies in Gradle (Konsist reads no `.kts`).
- When a rule changes, change its fixture in the same commit, and check that the fixture test failed before the fix.
