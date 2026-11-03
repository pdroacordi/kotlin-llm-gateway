---
id: bugs/arch-10-misses-co-web-exception-handler-and-problem-details-handler
type: bug
title: ARCH-10 lets a CoWebExceptionHandler or Boot's ProblemDetailsExceptionHandler register a second error mapper outside .error
status: active
severity: medium
tags: [konsist, architecture, errors, spring]
related: [bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations, patterns/one-error-funnel, patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ErrorEnvelopeIntegrationTest.kt
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/RuleFixturesTest.kt
---

## Summary

ARCH-10 matches references against a fixed name list (`ERROR_HANDLING_TYPES`) and does not follow supertypes. Two error-handling types that name none of the listed types pass the rule anywhere in an app module.

## Symptom

Found in the FND-04 review (Spring Framework 7.0.10, Boot 4.1.2, checked with `javap`):

- `org.springframework.web.server.CoWebExceptionHandler`: Spring 7's Kotlin-coroutine `abstract class ... implements WebExceptionHandler`. `@Component @Order(-3) class AuthErrors : CoWebExceptionHandler()` in `gateway.app.auth` passes ARCH-10 and runs before the gateway's mapper (order −2). For a Kotlin codebase this is the natural way to write a handler.
- `org.springframework.boot.webflux.autoconfigure.ProblemDetailsExceptionHandler`: annotated `@ControllerAdvice`, extends `ResponseEntityExceptionHandler`. `@Bean fun problems() = ProblemDetailsExceptionHandler()` in any app configuration passes ARCH-10 and answers 404/405/406/415 with `application/problem+json`.

`isErrorHandling("org.springframework.web.server.CoWebExceptionHandler")` is false: no list entry is a segment prefix of it. The runtime guard in `ErrorEnvelopeIntegrationTest` checks only `ErrorWebExceptionHandler` beans, so it misses both.

## Root cause

`ArchitectureRules.kt:66-78` lists names; `errorHandlingIn` (`:360`) flags only imports, inline names, annotations and star-import uses of those names. A subclass or a bean of a subclass of a listed type names only the subclass.

## Fix

Fixed in FND-04:

- `ArchitectureRules.kt:69`, `:70` and `:76` add `org.springframework.web.server.CoWebExceptionHandler`, `org.springframework.web.server.handler.ExceptionHandlingWebHandler` and `org.springframework.boot.webflux.autoconfigure.ProblemDetailsExceptionHandler` to `ERROR_HANDLING_TYPES` (now `:66-81`). The KDoc of `errorHandlingOnlyInErrorPackage` (`:214-219`) states the limit: names are matched, not types, so a typealias or subclass declared inside `.error` and used elsewhere is not seen.
- Three fixture files in `architecture-tests/src/fixtures/kotlin/arch-10-error-handling-outside-error-package/gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/`: `CoroutineErrorHandler.kt` (subclass of `CoWebExceptionHandler`), `ProblemDetailsWiring.kt` (returns a `ProblemDetailsExceptionHandler`) and `WrappedWebHandler.kt` (inline `ExceptionHandlingWebHandler(...)`).
- Runtime guard `ErrorEnvelopeIntegrationTest:145`: the `WebExceptionHandler` beans are exactly `GatewayErrorWebExceptionHandler` and `WebFluxResponseStatusExceptionHandler` (count 2), and `getBeanNamesForAnnotation(ControllerAdvice)` is empty. It also catches a subclass of a base class in `.error`, which no name list can see.
- Boot 4.1.2's `ProblemDetailsExceptionHandler` is package-private (`javap`), so application code cannot construct it; only `spring.webflux.problemdetails.enabled=true` registers it, and both the list entry and the bean guard cover that.

The Root cause lines refer to the file before the fix (`errorHandlingIn` is now at `:365`). Commit: the FND-04 commit (`Roadmap: FND-04`).

## Prevention

Added for FND-04: `RuleFixturesTest:130` "ARCH-10 fails on the coroutine handler, Boot's problem-details advice and the exception-handling decorator" asserts one violation per new fixture file; it was red before the list change ("expected ARCH-10 to flag imports ...CoWebExceptionHandler"). The bean guard passed on its first run, which confirms the set it asserts is the real one. General rule: when a rule is a name list, check the framework jars for every subtype of each listed type (`javap`, or `unzip -l` plus a grep) before the list ships, and pair the rule with a bean-level runtime assertion.
