---
id: gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query
type: gotcha
title: Spring 7's ExceptionHandlingWebHandler attaches a Reactor checkpoint with the raw query string to every error, as a suppressed exception
status: active
tags: [spring, webflux, reactor, logging, errors, privacy]
related: [bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint, gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame, bugs/committed-response-error-logged-twice-with-query-string, systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailureTest.kt
---

## Summary

In Spring Framework 7.0.10 (Reactor 3.8.8), every exception that reaches a `WebExceptionHandler` already carries a suppressed `reactor.core.publisher.FluxOnAssembly$OnAssemblyException` whose text names the request: `HTTP GET "/path?raw-query" [ExceptionHandlingWebHandler]`. Logging the exception prints that text in the stack trace's `Suppressed:` section, so the raw query string reaches the log even when no log message mentions it.

## Details

- `ExceptionHandlingWebHandler` adds a `CheckpointInsertingHandler` as the first entry of its handler list. That handler builds the description from `request.method`, `request.path` and `request.uri.rawQuery` (with `?` only when the query has text) and returns `Mono.error(ex).checkpoint(description)`. Read from the `spring-web-7.0.10.jar` bytecode with `javap -c -v` (constant `HTTP \u0001 "\u0001\u0001"` and `\u0001 [ExceptionHandlingWebHandler]`).
- `checkpoint(String)` is a light checkpoint: on error, Reactor adds an `OnAssemblyException` with the description to the error's suppressed list. The original exception object is mutated, and `Throwable` has no API to remove a suppressed exception.
- Minimal reproduction (Kotlin, Reactor on the classpath):

  ```kotlin
  val ex = IllegalStateException("boom")
  Mono.error<Unit>(ex).checkpoint("x?q=1").onErrorResume { Mono.empty() }.block()
  println(ex.stackTraceToString())   // ... Suppressed: ...OnAssemblyException: ... x?q=1
  ```

  The error is resumed before `.block()` on purpose: `.block()` on an erroring `Mono` adds its own suppressed exception (`#block terminated with an error`) to the thrown error, which would muddy the observation.
- `LoggableFailureTest:12-34` runs this reproduction with `HTTP GET "/v1/x?secret=1"` and asserts the checkpoint is attached (`:23`) before checking the copy.
- Turning off Spring's own `HttpWebHandlerAdapter` logger does not help: the text is on the exception, and any handler that logs it prints it.

## How to handle it

- Never log the exception a `WebExceptionHandler` receives. Log `LoggableFailure.of(ex)` (`gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt:17`), a copy of the chain without `FluxOnAssembly$OnAssemblyException` (`:4`, `:25`, `:33`) that keeps messages, frames (taken back from the checkpoint, `:28-32`), causes and other suppressed exceptions. The mapper does this at every log call with a throwable: `GatewayErrorWebExceptionHandler.kt:56`, `:95`, `:134`.
- Any later code that logs a throwable from the WebFlux chain (GW-02's stream error path, a filter) does the same.
- Test for it by sending a query string and asserting it is absent from the whole captured output, stack traces included (`ErrorEnvelopeIntegrationTest:132`, `:168`).

## Revision (2026-11-03)

Dropping the checkpoint also drops the failure's frames: the checkpoint cut the original's stack trace to one frame and holds the full trace itself ([gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame](reactor-checkpoint-truncates-error-stack-to-first-frame.md)). The copy must take the frames from the checkpoint before dropping it, as `mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailure.kt:26-33` does. `gateway-app`'s copy did not; it does since the FND-04 fix commit (`LoggableFailure.kt:28-32`; [bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint](../bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint.md)).
