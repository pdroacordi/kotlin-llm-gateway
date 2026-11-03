---
id: bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint
type: bug
title: Every error the mapper logged with a stack trace printed the raw query string, through the Reactor checkpoint Spring attaches
status: active
severity: low
tags: [errors, logging, webflux, reactor, privacy]
related: [gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query, bugs/committed-response-error-logged-twice-with-query-string, systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailureTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ErrorEnvelopeIntegrationTest.kt
---

## Summary

`GatewayErrorWebExceptionHandler` logged the exception it received. Spring 7 attaches a Reactor checkpoint to every error before the mapper sees it, as a suppressed exception whose text is `HTTP <method> "<path>?<raw query>"`. Every 5xx stack trace (and the committed-response and fallback ERROR lines) therefore printed the request's raw query string, which FND-04 section 5 lists under "Never recorded".

## Symptom

Found by the implementer on 2026-11-03 while proving the fix for [bugs/committed-response-error-logged-twice-with-query-string](committed-response-error-logged-twice-with-query-string.md): with Spring's `HttpWebHandlerAdapter` logger switched off, the captured output of `GET /test-probe/committed?secret-query=...` still held `secret-query`. The same held for `GET /test-probe/boom?secret-query=...` (500): the mapper's own ERROR line was followed by a stack trace whose `Suppressed:` section named the checkpoint with the query.

## Root cause

Spring Framework 7.0.10's `ExceptionHandlingWebHandler` puts a `CheckpointInsertingHandler` first in its exception-handler list. For every error it returns `Mono.error(ex).checkpoint("HTTP " + method + " \"" + path + query + "\" [ExceptionHandlingWebHandler]")`, where `query` is `"?" + uri.rawQuery` when the query has text (bytecode, `javap`), and Reactor adds a `reactor.core.publisher.FluxOnAssembly$OnAssemblyException` carrying that description to `ex.suppressed` ([gotcha](../gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)). The mapper passed `ex` itself to `log.error(...)`, so Logback printed the suppressed exception. Suppressed exceptions cannot be removed from a `Throwable`.

## Fix

Fixed in FND-04. New `gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt`: `LoggableFailure.of(failure)` (`:16`) copies the failure chain for logging. Each copy's message is the original's `toString()`, its stack frames are the original's, causes are copied recursively, and suppressed exceptions are copied except `FluxOnAssembly$OnAssemblyException` (`:4`, `:25-27`); recursion stops at depth 32 (`:3`). The mapper logs `LoggableFailure.of(...)` at every log call with a throwable: `GatewayErrorWebExceptionHandler.kt:56` (after commit), `:95` (fallback) and `:134` (5xx). The logged class now reads `...LoggableFailure: java.lang.IllegalStateException: probe-secret`. Commit: the FND-04 commit (`Roadmap: FND-04`).

## Prevention

Added for FND-04:

- `ErrorEnvelopeIntegrationTest:115` "a bug gives 500 with the request ID, no exception message, and one ERROR line" now calls `/test-probe/boom?secret-query=value-from-the-client` and asserts the query is absent from the whole captured output (`:132`); red before `LoggableFailure`.
- `ErrorEnvelopeIntegrationTest:157` asserts the same for the committed path.
- `LoggableFailureTest:12`: the copy drops the checkpoint and keeps message, frames, cause and other suppressed exceptions.

After the fix, "secret-query" appears 0 times in the app test output. Review rule: a log assertion for "never recorded" data must send that data in the request (a query string, a header) and grep the whole output, stack traces included; checking only the log line misses suppressed and cause sections.
