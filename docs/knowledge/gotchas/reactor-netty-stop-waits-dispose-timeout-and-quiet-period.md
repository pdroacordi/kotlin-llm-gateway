---
id: gotchas/reactor-netty-stop-waits-dispose-timeout-and-quiet-period
type: gotcha
title: With server.shutdown=immediate, a Boot WebFlux app with an open connection still takes about 5 s to stop (3 s dispose wait plus a 2 s event-loop quiet period)
status: active
tags: [spring-boot, reactor-netty, shutdown, webflux]
related: [systems/mock-provider]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/resources/application.yaml
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/ServerConfiguration.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/ShutdownIntegrationTest.kt
---

## Summary

`server.shutdown: immediate` skips Boot's graceful phase, but in Spring Boot 4.1.2 with Reactor Netty 1.3.8 two waits remain:

1. `NettyWebServer.stop` calls `DisposableServer.disposeNow()`, which waits up to 3 s for open connections.
2. Closing the event loops waits a 2 s quiet period (`LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD`).

A request held open (a `hang` fault, a slow stream) makes the stop take about 5 s.

## Details

- `javap -c` on `reactor-netty-core-1.3.8.jar`: `DisposableChannel.disposeNow()` is `disposeNow(Duration.ofSeconds(3))`. `LoopResources.DEFAULT_SHUTDOWN_QUIET_PERIOD` is read from `reactor.netty.ioShutdownQuietPeriod`.
- `javap -c` on `spring-boot-reactor-netty-4.1.2.jar`: `NettyWebServer` uses its `lifecycleTimeout`, when set, for both `disposeNow(timeout)` at stop and `bindNow(timeout)` at start. The default `bindNow()` waits 45 s.
- `ShutdownIntegrationTest` (FND-03) measured 5 s without the two settings below and under 2 s with them.

## How to handle it

- `spring.reactor.netty.shutdown-quiet-period: 0s` (`mock-provider/src/main/resources/application.yaml:20`).
- `NettyReactiveWebServerFactory.setLifecycleTimeout(...)` through a `WebServerFactoryCustomizer` (`ServerConfiguration.kt:18-20`; 3 s, `:36`). The same value is the startup bind timeout, so keep it above the slowest bind you expect (a CPU-starved CI container), not at the shortest stop you would like.
