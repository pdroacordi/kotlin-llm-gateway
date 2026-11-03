---
id: bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint
type: bug
title: gateway-app logs every bug's stack trace with one frame, because LoggableFailure drops the checkpoint that holds the rest
status: active
severity: medium
tags: [errors, logging, webflux, reactor]
related: [gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame, bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query, systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailureTest.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailure.kt
---

## Summary

Every throwable `GatewayErrorWebExceptionHandler` logs (5xx, after commit, fallback) is printed with only the frame where it was created. The caller frames are lost, so an ERROR line from a bug in production shows one line of stack.

## Symptom

Found in the FND-03 review (2026-11-03), from the FND-03 implementer's report. Reproduced with Reactor 3.8.8 on JDK 21 (plain `java` with `reactor-core-3.8.8.jar` on the classpath): an exception with 8 frames, passed through `Mono.error(e).checkpoint("HTTP GET \"/x?secret=1\"").onErrorResume { Mono.empty() }.block()`, has 1 frame afterwards; its suppressed `FluxOnAssembly$OnAssemblyException` has all 8. The mock provider logs the full trace for the same path because its copy of `LoggableFailure` takes the frames back from the checkpoint.

## Root cause

A Reactor checkpoint shortens the failure's own stack trace to its first frame and moves the full trace into the suppressed `OnAssemblyException` ([gotcha](../gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame.md)). Spring 7's `ExceptionHandlingWebHandler` adds that checkpoint to every error. `LoggableFailure.copyOf` copied `failure.stackTrace` (`gateway-app/.../error/LoggableFailure.kt:24` before the fix), which is already the one-frame trace, and then dropped every `OnAssemblyException` (`:25-27` before the fix), which is the only place that still holds the frames.

`LoggableFailureTest.kt:26` (before the fix) checked `assertArrayEquals(failure.stackTrace, copy.stackTrace)` after the checkpoint ran. Both sides have one frame, so the assertion passed.

## Fix

Fixed for FND-04, in a commit of its own during the FND-03 cycle. `gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt:24-34`: the suppressed exceptions are split into checkpoints and others (`:25`); the copy's frames are those of the first checkpoint whose trace is longer than the failure's own, else the failure's (`:28-32`); the checkpoint itself is still dropped (it carries the raw query string) and the others are copied (`:33`). It now matches `mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailure.kt:25-35`. Commit: the FND-04 fix commit (`Roadmap: FND-04`).

## Prevention

Added with the fix:

- `gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailureTest.kt:16` records the frames before the checkpoint runs; `:24` fails if the checkpoint stops truncating (the test would then prove nothing); `:29-30` compare the copy with the recorded frames and require a frame of the test class. Red before the fix. The mock's copy has the same test (`mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailureTest.kt:15`, `:23`, `:29-30`).
- Review rule, kept: a test that compares a value with itself after a transformation proves nothing about that value. Capture the expected value before the code under test can change it.
- The two copies of `LoggableFailure` are kept in step by hand (the mock imports nothing of ours, ADR-FND-03-003): a change to one needs the same change and test in the other.
