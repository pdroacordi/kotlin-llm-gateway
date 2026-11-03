---
id: gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay
type: gotcha
title: After a delay, Spring's coRouter handlers and CoWebFilters continue on kotlinx's single DefaultExecutor thread, which BlockHound does not guard
status: active
tags: [spring, webflux, coroutines, blockhound, performance]
related: [bugs/mock-serving-after-delay-runs-on-default-executor, systems/mock-provider]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/SseBodyInserter.kt
---

## Summary

Spring Framework 7.0.10 starts `coRouter` handlers (`CoRouterFunctionDsl`) and `CoWebFilter`s with `mono(Dispatchers.Unconfined)`; `Flow.asFlux()` also defaults to `Unconfined`. `Unconfined` has no timer, so `delay` uses kotlinx-coroutines' `DefaultExecutor`. That is one thread per JVM, and the coroutine keeps running on it after the delay, until its next suspension. Seen with kotlinx-coroutines 1.11.0.

## Details

- `javap -c` on `spring-webflux-7.0.10.jar` (`CoRouterFunctionDsl`, `CoRouterFunctionDsl$CoroutineContextAwareHandlerFunction`) and `spring-web-7.0.10.jar` (`CoWebFilter`) shows `Dispatchers.getUnconfined()`.
- A plain `launch(Dispatchers.Unconfined) { delay(100) }` logs `main` before the delay and `kotlinx.coroutines.DefaultExecutor` after it.
- In the running mock (boot jar, JDK 25), a 500 from a step with `ttftMs: 50` was logged by the error mapper on thread `[DefaultExecutor]`. The same step with no latency was logged on `[ctor-http-nio-6]`.
- `DefaultExecutor` is not a non-blocking thread for BlockHound (only Reactor's `parallel` schedulers and Netty event loops are). A blocking call after the first `delay` passes every BlockHound test. Every timed continuation in the process also queues behind the others on that one thread.

## How to handle it

- Wait on a Reactor timer instead: `Mono.delay(Duration.ofMillis(ms)).awaitSingleOrNull()`, or `withContext(Schedulers.parallel().asCoroutineDispatcher()) { delay(ms) }`. The continuation then resumes on a `parallel` thread, which BlockHound guards and which has one thread per core.
- To prove it, a test can assert that the thread after the wait is not `DefaultExecutor`, or make a blocking call there and expect BlockHound's error.
