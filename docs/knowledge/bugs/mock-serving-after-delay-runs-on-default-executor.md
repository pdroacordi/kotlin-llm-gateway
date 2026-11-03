---
id: bugs/mock-serving-after-delay-runs-on-default-executor
type: bug
title: mock-provider runs everything after its first latency delay on kotlinx's single DefaultExecutor thread, unguarded by BlockHound
status: active
severity: medium
tags: [mock-provider, coroutines, blockhound, performance, latency]
related: [gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay, systems/mock-provider, patterns/raw-sse-frames-flushed-per-event]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/ServingThreadIntegrationTest.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/ReactorDelayTest.kt
---

## Summary

Every mock request with latency or a held fault continues on the one `kotlinx.coroutines.DefaultExecutor` thread after its first `delay`. This covers rendering the response, the stream's later events, error replies and the mapper. BlockHound does not watch that thread, so the FND-03 claim that the latency simulation is "proven non-blocking" covers only the code before the first delay. Under load, all timed continuations in the process share that one thread.

## Symptom

Found in the FND-03 review (2026-11-03). In the boot jar, with a scenario `{"otherwise":{"latency":{"ttftMs":50},"error":{"status":429,"headers":{"bad name":"x"}}}}`, the mapper's ERROR line was logged on `[DefaultExecutor]`. Without the latency it was logged on `[ctor-http-nio-6]`.

## Root cause

`Serving.pause` and `Serving.hold` called `kotlinx.coroutines.delay` (`Serving.kt:228`, `:175`, `:149` before the fix). The handlers run under Spring's `mono(Dispatchers.Unconfined)`, and the stream flow under `asFlux()`'s `Unconfined` (`SseBodyInserter.kt:23`). `Unconfined` has no timer, so the delay is scheduled on `DefaultExecutor`, and the continuation runs there ([gotcha](../gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)).

Consequences:

- A `Thread.sleep` or other blocking call after a delay, in `json`, `stream` or `ending`, passes every BlockHound-instrumented test, and stalls every delayed request in the process.
- OPS-01 runs k6 against the gateway with the mock at a fixed latency. Every delayed continuation and every stream event is then serialized on one thread, so the mock's latency at 200 concurrent streams includes queueing on that thread.

## Fix

Fixed in FND-03. Every wait goes through `awaitDelay` (`mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt:253-255`), which awaits `Mono.delay(Duration.ofMillis(ms))` on Reactor's `parallel` timer, so the continuation runs on a `parallel` thread: one per core, and marked non-blocking for BlockHound. Callers: `pause` (`:229`), `hold` (`:176`) and the `stall` hold in `ending` (`:150`). Cancelling the coroutine cancels the timer. Commit: the FND-03 commit (`Roadmap: FND-03`).

## Prevention

Added for FND-03:

- `ServingThreadIntegrationTest:58` "a handler resumes on a Reactor parallel thread after the wait" (the thread name starts with `parallel-`, never `DefaultExecutor`); `:73` "BlockHound catches a blocking call made after the wait" (a `Thread.sleep(1)` after `awaitDelay` gives 500); `:87` a reply after scenario latency is still served, stream and non-stream.
- `ReactorDelayTest:15` pins the cause (`delay` under `Unconfined` resumes on `DefaultExecutor`) and `:22` the fix (`awaitDelay` resumes on `parallel-*`), so a Spring or kotlinx upgrade that changes either shows up.
- The spec's deliberate `Thread.sleep` violation now also fails when placed after a latency wait.
- [patterns/raw-sse-frames-flushed-per-event](../patterns/raw-sse-frames-flushed-per-event.md) says to wait on a Reactor timer, never `kotlinx.coroutines.delay`; GW-02 follows it when the gateway streams.
