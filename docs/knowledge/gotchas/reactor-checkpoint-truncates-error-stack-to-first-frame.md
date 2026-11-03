---
id: gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame
type: gotcha
title: A Reactor checkpoint cuts the failing exception's stack trace to its first frame and moves the full trace into the suppressed OnAssemblyException
status: active
tags: [reactor, errors, logging, webflux]
related: [bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailure.kt
---

## Summary

In Reactor 3.8.8, when an error passes a `checkpoint(...)`, the original exception object is mutated twice: a `reactor.core.publisher.FluxOnAssembly$OnAssemblyException` is added to its suppressed list, and its own `stackTrace` is replaced by its first frame. The full original trace is now the `OnAssemblyException`'s trace.

## Details

Reproduction (Java, `reactor-core-3.8.8.jar` and `reactive-streams-1.0.4.jar` on the classpath, JDK 21):

```java
IllegalStateException e = make();          // 8 frames
Mono.error(e).checkpoint("HTTP GET \"/x?q=1\"").onErrorResume(t -> Mono.empty()).block();
e.getStackTrace().length;                  // 1: Repro.deeper(Repro.java:4)
e.getSuppressed()[0].getStackTrace().length; // 8, first frame Repro.deeper(Repro.java:4)
```

Spring Framework 7.0.10 runs every WebFlux error through such a checkpoint (`ExceptionHandlingWebHandler`; [gotcha](spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)). So any code that drops the checkpoint to keep its text out of a log also drops the frames, unless it copies them first.

## How to handle it

- When logging a copy without the checkpoint, take the frames from the first `OnAssemblyException` whose trace is longer than the failure's own: `mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/LoggableFailure.kt:26-33` and `gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt:25-32`.
- In a test, record the frame count before the checkpoint runs. Comparing the copy with the already-truncated original passes vacuously ([bug](../bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint.md)).
