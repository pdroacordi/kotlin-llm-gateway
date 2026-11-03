# Knowledge base index

Flat scan list, newest first within each category. See [README.md](README.md) for the schema and the self-improvement rule. Update this file in the same edit that adds or changes an entry; `scripts/docs-check.sh` enforces it.

## Systems

The current contract of each system. Read before changing the system.

(none yet: the first ones arrive with GW-01 and GW-04)

## Patterns

- [architecture-rule-with-violating-fixture](patterns/architecture-rule-with-violating-fixture.md) — each Konsist rule is a function over a scope, applied to the real modules (must pass) and to an uncompiled fixture that breaks it (must fail), so no rule can go silently vacuous.

## Bugs

- [architecture-test-inputs-miss-nested-module-sources](bugs/architecture-test-inputs-miss-nested-module-sources.md) — `*/src/**/*.kt` inputs missed nested modules, so `:architecture-tests:test` stayed UP-TO-DATE/FROM-CACHE and ARCH-8 did not run; inputs are now `**/*.kt` (medium, fixed in FND-01).
- [arch-6-misses-qualified-and-typealiased-wiring-annotations](bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations.md) — `@org.springframework...Component` written inline, a star import or a typealias passed ARCH-6 outside app modules; the rule now resolves annotation text, star imports and typealiases (medium, fixed in FND-01).
- [arch-1-misses-jdk-clients-in-core](bugs/arch-1-misses-jdk-clients-in-core.md) — JDK HTTP/SQL clients are on every classpath, so inline `java.net.http...` or an `HttpURLConnection` import passed ARCH-1 in core; now `java.net`/`javax.net` are forbidden (URI allowlist), inline names are matched and `checkCoreClasspath` guards build scripts (medium, fixed in FND-01).

## Gotchas

- [konsist-annotation-fqn-unresolved-for-qualified-star-typealias](gotchas/konsist-annotation-fqn-unresolved-for-qualified-star-typealias.md) — Konsist resolves an annotation's `fullyQualifiedName` only through an explicit import; inline-qualified, star-imported and typealiased annotations come back unresolved.
- [konsist-paths-and-module-names-use-platform-separator](gotchas/konsist-paths-and-module-names-use-platform-separator.md) — `projectPath` and nested `moduleName` use `\` on Windows and `/` on Linux; `ModuleFile` normalizes both, so compare through it.
- [konsist-wildcard-import-name-omits-star](gotchas/konsist-wildcard-import-name-omits-star.md) — a star import's `name` has no `.*` (`isWildcard` is true); match prefixes on a segment boundary.
- [konsist-does-not-scan-kts-files](gotchas/konsist-does-not-scan-kts-files.md) — Konsist scopes hold `.kt` only; no rule sees a dependency added in a build script, so `checkCoreClasspath` guards core's resolved classpath.
- [detekt-2-check-runs-plain-detekt-task](gotchas/detekt-2-check-runs-plain-detekt-task.md) — detekt 2.0.0-alpha.6 makes `check` depend on the untyped `detekt` task; swap in `detektMain`/`detektTest`.
- [ktlint-gradle-lints-kotlin-dsl-generated-sources](gotchas/ktlint-gradle-lints-kotlin-dsl-generated-sources.md) — kotlin-dsl generated sources are in `main`; exclude them by absolute `Path.startsWith`, computed outside the lambda for the configuration cache.
- [gradle-version-catalog-alias-prefix-needs-asprovider](gotchas/gradle-version-catalog-alias-prefix-needs-asprovider.md) — `ktlint` beside `ktlint-gradle` makes `libs.versions.ktlint` a group; use `.asProvider()`.
- [gradle-task-selectors-skip-included-builds](gotchas/gradle-task-selectors-skip-included-builds.md) — bare task names skip the included `build-logic`; here the root `check`, `ktlintCheck` and `ktlintFormat` are wired to its tasks.
- [git-bash-grep-ignore-case-fixed-strings-aborts](gotchas/git-bash-grep-ignore-case-fixed-strings-aborts.md) — GNU grep 3.0 (Git Bash) aborts on `-i` with `-F`; lowercase both sides and fail on grep exit > 1.

## Runs

(none yet)
