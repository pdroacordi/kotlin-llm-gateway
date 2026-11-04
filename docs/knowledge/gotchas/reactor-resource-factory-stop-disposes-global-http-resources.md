---
id: gotchas/reactor-resource-factory-stop-disposes-global-http-resources
type: gotcha
title: Closing a fully started Boot WebFlux context disposes Reactor Netty's global HttpResources, under every other context in the JVM
status: active
tags: [spring, spring-boot, reactor-netty, testing, shutdown]
related: [bugs/started-test-contexts-dispose-global-reactor-resources]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/RevocationLagTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/StartedContextIsolationTest.kt
  - gateway-app/src/testFixtures/kotlin/io/github/pdroacordi/llmgateway/gateway/app/OwnReactorResources.kt
---

## Summary

On Spring 7.0.10 and Boot 4.1.2, `ReactorResourceFactory` defaults to `useGlobalResources = true`, and its `stop()` calls `HttpResources.disposeLoopsAndConnectionsLater(...).block()`. So one context closing shuts the event loops that every other context in the JVM still runs on.

## Details

Read from the bytecode of `spring-web-7.0.10.jar` and `spring-context`; no isolated reproduction was run.

- `stop()` runs from the lifecycle processor, on `close()` of a context that finished its refresh.
- `destroy()` calls `stop()` only when `applicationContext == null`, so inside a context it does nothing.
- `getLoopResources()` starts the factory lazily, so it is already running when the web server is created in `onRefresh`.
- A start that fails during bean creation should not dispose the resources: `refresh()`'s catch reaches `LifecycleProcessor.stop()` only through the `lifecycleProcessor` field, which `finishRefresh` initialises. A failure in `finishRefresh` or later, a port bind for example, can reach `stop()`.
- A context started after the close asks `HttpResources.get()` and gets new resources, so it works. A context started before it keeps the disposed loops.

## How to handle it

A test that starts and closes its own context in a JVM holding cached contexts starts it with `isolatedGateway()`, from `gateway-app`'s `testFixtures`. In production one context owns the JVM, so nothing changes.

## Revision (2026-11-04)

Reproduced, and narrower than the summary says. `@SpringBootTest` contexts are not on the global resources: Boot's `DisableReactorResourceFactoryGlobalResourcesBeanPostProcessor` (`spring-boot-test`) turns them off, so a cached context's server survives the close. Only a context from `SpringApplicationBuilder` is global, and its close replaces `HttpResources` (a diagnostic saw the instance change, and the close took 2 s, the quiet period).

- A cached context's server answered after the close, and so did a `WebTestClient`, even one built before it.
- A cached context's call through the provider adapter hung: `RevocationLagTest` with the guard removed timed out at 30 s on its last chat, and passed with it. Which of the adapter's resources is dead was not isolated.
