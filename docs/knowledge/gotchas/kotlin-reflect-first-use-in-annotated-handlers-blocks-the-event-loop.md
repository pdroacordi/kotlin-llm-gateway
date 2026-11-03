---
id: gotchas/kotlin-reflect-first-use-in-annotated-handlers-blocks-the-event-loop
type: gotcha
title: The first call of a Kotlin annotated handler runs kotlin-reflect on the event loop, which reads its built-ins from the jar and takes ReentrantLocks
status: active
tags: [spring, webflux, kotlin, blockhound]
related: [gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use, systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/EventLoopWarmup.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/BlockHoundIntegrationTest.kt
---

## Summary

Spring WebFlux 7.0.10 invokes a Kotlin `@RestController` method through `InvocableHandlerMethod$KotlinDelegate.invokeFunction`, which calls `ReflectJvmMapping.getKotlinFunction`. With kotlin-reflect 2.4.21 that first use reads `.kotlin_builtins` from the jar and takes `ReentrantLock`s, on the Netty event loop. `coRouter` handlers do not take this path, so `mock-provider` never showed it.

## Details

Seen on the first BlockHound run in `gateway-app` (2026-11-03), with 2000 requests to `ProbeController` over a real socket at concurrency 32:

- All 2000 got 500: 1982 `Blocking call! java.io.RandomAccessFile#readBytes` (`BuiltInsBinaryVersion.readFrom` through `JvmBuiltInsPackageFragmentProvider.findPackage`), 18 `jdk.internal.misc.Unsafe#park` and 1 `java.lang.Thread.onSpinWait` (a contended `LockBasedStorageManager` lock).
- The failed read is not cached, so with BlockHound every later call to a handler fails too. Without BlockHound it is a stall of each event loop that gets there first, plus lock contention between them.
- The `Blocking call!` error turns into a 500 from `IllegalStateException: Invocation failure`, so a test asserting only "a 500 with BlockingOperationError" passes for the wrong call. Assert the blocked method's name.

## How to handle it

`EventLoopWarmup` (`EventLoopWarmup.kt:18`) resolves `kotlinFunction`, its parameters and their types for every Kotlin handler of each `RequestMappingHandlerMapping` in `afterSingletonsInstantiated`, before the server accepts requests. With it, the same 2000 requests got 200. It covers handlers registered at startup only. Do not allow-list `InvocableHandlerMethod$KotlinDelegate.invokeFunction` in BlockHound: the handler itself runs inside it, so the allowance would also hide its blocking calls.
