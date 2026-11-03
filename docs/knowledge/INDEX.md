# Knowledge base index

Flat scan list, newest first within each category. See [README.md](README.md) for the schema and the self-improvement rule. Update this file in the same edit that adds or changes an entry; `scripts/docs-check.sh` enforces it.

## Systems

The current contract of each system. Read before changing the system.

- [request-context-and-errors](systems/request-context-and-errors.md) — gateway-generated UUIDv7 `x-request-id` on every response, validated and echoed `x-client-request-id`, every error as the four-member OpenAI envelope from one `ErrorWebExceptionHandler` in `<app root>.error` (FND-04, ARCH-10); the envelope is always written before commit, and logged throwables never carry the query string.

## Patterns

- [one-error-funnel](patterns/one-error-funnel.md) — every failure becomes a sealed `GatewayError`, one exhaustive core table gives status/type/code/message, one `WebExceptionHandler` in `<app root>.error` writes the OpenAI envelope; no `@ControllerAdvice` (ARCH-10). Revised after the FND-04 review: always write before commit, log a copy without Reactor's checkpoint, pin the handler bean set.
- [request-context-coroutine-element](patterns/request-context-coroutine-element.md) — the first `CoWebFilter` installs per-request values as a coroutine context element plus `MDCContext`, mirrored in an exchange attribute for the exception handler; core takes values as parameters.
- [architecture-rule-with-violating-fixture](patterns/architecture-rule-with-violating-fixture.md) — each Konsist rule is a function over a scope, applied to the real modules (must pass) and to an uncompiled fixture that breaks it (must fail), so no rule can go silently vacuous.

## Bugs

- [loggable-failure-logs-one-frame-after-reactor-checkpoint](bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint.md) — `gateway-app`'s `LoggableFailure` copies the stack trace a Reactor checkpoint already cut to one frame and drops the checkpoint holding the rest, so every logged bug showed one frame; the test compared the copy with the truncated original; the copy now takes the frames from the checkpoint (medium, fixed in the FND-04 fix commit).
- [mapper-error-log-carries-raw-query-via-reactor-checkpoint](bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint.md) — every stack trace the mapper logged printed the raw query string through the checkpoint Spring's `ExceptionHandlingWebHandler` attaches as a suppressed exception; the mapper now logs `LoggableFailure.of(ex)`, which keeps the frames since the FND-04 fix commit (low, fixed in FND-04).
- [error-mapper-disconnect-check-turns-errors-into-empty-200](bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md) — the mapper skipped the write for anything `DisconnectedClientHelper` accepts, which matches phrases in any cause's message, so an error quoting "broken pipe" got 200 with an empty body; the envelope is now always written before commit and the check only lowers the log level (high, fixed in FND-04).
- [arch-10-misses-co-web-exception-handler-and-problem-details-handler](bugs/arch-10-misses-co-web-exception-handler-and-problem-details-handler.md) — a subclass of Spring 7's `CoWebExceptionHandler`, or Boot's `ProblemDetailsExceptionHandler`, named no listed type, so a second mapper passed ARCH-10; both and `ExceptionHandlingWebHandler` are now listed, with fixtures and a runtime bean-set guard (medium, fixed in FND-04).
- [committed-response-error-logged-twice-with-query-string](bugs/committed-response-error-logged-twice-with-query-string.md) — after commit `HttpWebHandlerAdapter` logged a second ERROR line with the raw query string and no request ID; that logger is now `off` (low, fixed in FND-04).
- [architecture-test-inputs-miss-nested-module-sources](bugs/architecture-test-inputs-miss-nested-module-sources.md) — `*/src/**/*.kt` inputs missed nested modules, so `:architecture-tests:test` stayed UP-TO-DATE/FROM-CACHE and ARCH-8 did not run; inputs are now `**/*.kt` (medium, fixed in FND-01).
- [arch-6-misses-qualified-and-typealiased-wiring-annotations](bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations.md) — `@org.springframework...Component` written inline, a star import or a typealias passed ARCH-6 outside app modules; the rule now resolves annotation text, star imports and typealiases (medium, fixed in FND-01).
- [arch-1-misses-jdk-clients-in-core](bugs/arch-1-misses-jdk-clients-in-core.md) — JDK HTTP/SQL clients are on every classpath, so inline `java.net.http...` or an `HttpURLConnection` import passed ARCH-1 in core; now `java.net`/`javax.net` are forbidden (URI allowlist), inline names are matched and `checkCoreClasspath` guards build scripts (medium, fixed in FND-01).

## Gotchas

- [spring-exception-handling-web-handler-checkpoint-carries-raw-query](gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md) — Spring 7.0.10's `ExceptionHandlingWebHandler` adds a Reactor checkpoint `HTTP <method> "<path>?<raw query>"` to every error as a suppressed exception that cannot be removed; log a copy without it (`LoggableFailure`).
- [spring-disconnected-client-helper-matches-phrases-in-cause-chain](gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md) — Spring 7.0.10's `DisconnectedClientHelper` returns true when any cause's message contains "broken pipe" or "connection reset by peer"; use it to pick a log level, never to skip writing a response.
- [jdk-drbg-seeds-lazily-on-first-nextbytes](gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes.md) — `SecureRandom.getInstance("DRBG")` reads entropy on the first `nextBytes`, not in `getInstance`. Every draw takes the instance lock. The reseed when the counter overflows, after about 2^31 draws, reads only from memory.
- [spring-httpstatus-deprecated-aliases-never-resolved](gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md) — `HttpStatus.resolve(413)` is `CONTENT_TOO_LARGE` and `resolve(422)` is `UNPROCESSABLE_CONTENT`, so `==` on the deprecated `PAYLOAD_TOO_LARGE` or `UNPROCESSABLE_ENTITY` never matches; compare integers.
- [detekt-forbiddenvoid-flags-mono-void](gotchas/detekt-forbiddenvoid-flags-mono-void.md) — `ForbiddenVoid` is active by default with `ignoreUsageInGenerics: false`, so every `Mono<Void>` is a finding; set `ignoreUsageInGenerics: true`.
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
