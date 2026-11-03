---
id: gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use
type: gotcha
title: The first await on a Publisher runs a ServiceLoader jar read in ReactiveFlowKt's static initializer, on whatever thread awaits first
status: active
tags: [coroutines, reactor, blockhound, webflux]
related: [systems/mock-provider, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/MockConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/EventLoopWarmup.kt
---

## Summary

In kotlinx-coroutines 1.11.0, `kotlinx.coroutines.reactive.ReactiveFlowKt`'s static initializer runs `ServiceLoader.load(ContextInjector::class.java)` and iterates it. That reads `META-INF/services` from the jars. `Mono.awaitSingleOrNull` (kotlinx-coroutines-reactor `MonoKt`) and the `Publisher` awaits (`AwaitKt`) call `ReactiveFlowKt.injectCoroutineContext`, so the first await in the JVM triggers the read. In WebFlux that is the first `CoWebFilter` chain call or `awaitSingle` on a request, on a Netty event loop. BlockHound reports it as `RandomAccessFile#readBytes`.

## Details

- `javap -c` on `kotlinx-coroutines-reactive-1.11.0.jar`: `ReactiveFlowKt.<clinit>` calls `ServiceLoader.load(ContextInjector, ContextInjector.getClassLoader())`, `.iterator()`, then `asSequence().toList()`. `MonoKt` and `AwaitKt` each reference `ReactiveFlowKt.injectCoroutineContext`.
- `kotlinx-coroutines-reactor` registers `ReactorContextInjector` as a service, so the loader always opens jars.
- Without BlockHound this is a single short block of an event loop at the first request. With BlockHound the static initializer fails on that request, and every later await in the JVM gets `NoClassDefFoundError: Could not initialize class ...ReactiveFlowKt`. In `gateway-app`, without the warm-up, 15 of 47 tests failed that way (2026-11-03).

## How to handle it

Initialise the class at startup, where blocking is allowed: `Class.forName("kotlinx.coroutines.reactive.ReactiveFlowKt")` in a bean factory method (`MockConfiguration.kt:75`). `gateway-app` does the same in `EventLoopWarmup` (`EventLoopWarmup.kt:19`).
