---
id: patterns/one-error-funnel
type: pattern
title: Every failure goes through one funnel - sealed error, core reply table, one WebExceptionHandler
status: active
tags: [errors, webflux, api-contract, openai-compat, architecture]
related: [patterns/request-context-coroutine-element, patterns/architecture-rule-with-violating-fixture, bugs/error-mapper-disconnect-check-turns-errors-into-empty-200, bugs/arch-10-misses-co-web-exception-handler-and-problem-details-handler, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/ErrorReply.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslator.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
---

## Summary

An HTTP service with several error sources (filters, handler mapping, codecs, handlers, bugs) produces one error shape by sending every failure through the same three steps:
1. Translate the exception into a sealed error type.
2. Map that type through one pure table to status and body fields.
3. Write it with one low-level exception handler.

## Details

- **Sealed type in core.** `GatewayError` lists the expected failures. Core returns them as values and never throws them.
- **One table in core.** `GatewayError.toReply(requestId): ErrorReply` is a single exhaustive `when` that gives status (`Int`), `type`, `code`, message and extra headers. Because the `when` is exhaustive, a new variant does not compile until it has a row. The table is unit-tested row by row, with no framework.
- **Transport at the edge only.** A handler or filter that must fail raises `GatewayErrorException(error)`, an app-side exception without a stack trace.
- **Translator.** `Throwable.toGatewayError(method, path)` turns framework exceptions into variants by the HTTP status they carry, not by concrete class, which survives framework renames such as Spring 7's `ContentTooLargeException`. Anything unknown becomes `Internal`.
- **One writer.** A `WebExceptionHandler` that also implements Spring Boot's `ErrorWebExceptionHandler`, so Boot's default backs off. It sees errors from filters as well as handlers. It writes the DTO through the configured codecs, falls back to a constant body if writing fails, and leaves committed responses (streams) to the streaming code.
- **Guard.** Konsist ARCH-10 allows `@ControllerAdvice`, `@ExceptionHandler` and `WebExceptionHandler` only in `<app root>.error`, with a violating fixture.

Canonical use: FND-04 spec, section 5 (`docs/specs/FND-04-request-context-and-errors.md`).

## Why

- `@ControllerAdvice` covers controller errors, and errors before a handler is mapped through `DispatchExceptionHandler`. A `WebExceptionHandler` covers "the chain of WebFilter instances and the target WebHandler" (Spring Framework WebFlux reference). Mixing the two gives two code paths that drift.
- Keeping status, `type` and `code` in one table makes the wire contract (here OpenAI's, ADR-004) reviewable in one place.
- Sources:
  - https://docs.spring.io/spring-framework/reference/web/webflux/reactive-spring.html
  - https://docs.spring.io/spring-framework/reference/web/webflux/dispatcher-handler.html
  - https://docs.spring.io/spring-boot/reference/web/reactive.html

## Gotchas / pitfalls

- A single `@RestControllerAdvice` added later silently takes over controller and handler-mapping errors. That is why ARCH-10 exists.
- `spring.webflux.problemdetails.enabled=true` registers Boot's own advice: a second mapper by configuration.
- Do not pass exception messages into the body. Decoding errors can quote request bytes, and bug messages leak internals.
- Order matters only against handlers that remain. Boot's default is −1 and WebFlux's status handler is 0, so use −2.
- A committed response cannot change its status. The streaming code must turn failures into an in-band error event before they reach the handler.

## Revision (2026-11-03)

Three rules from the FND-04 review, each now part of the funnel:
- Before commit the handler always writes the body. A guess that the client disconnected (Spring's `DisconnectedClientHelper` matches phrases in any cause's message) decides neither the write nor, for the original exception, the log level; skipping the write produced 200 with an empty body, and lowering the level hid 5xx sent to live clients ([bug](../bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md)). It may only classify a write that failed.
- After commit, log, then return a stackless stand-in of your own, never the original: Reactor Netty logs what it gets, checkpoint included, and Spring ends the response normally for an exception the helper accepts ([gotcha](../gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md)).
- Log a copy of the exception without Reactor's checkpoint (`LoggableFailure.of(ex)`): Spring attaches one naming the raw query string to every error ([gotcha](../gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)).
- The architecture rule's name list covers every subtype in the framework jars (Spring 7's `CoWebExceptionHandler`, Boot's `ProblemDetailsExceptionHandler`, `ExceptionHandlingWebHandler`), and a runtime test pins the exact set of `WebExceptionHandler` beans and the absence of advice beans ([bug](../bugs/arch-10-misses-co-web-exception-handler-and-problem-details-handler.md)).
