---
id: bugs/started-test-contexts-dispose-global-reactor-resources
type: bug
title: gateway-app's test suite stays green only by class order, because config tests dispose the global Reactor resources
status: active
severity: low
tags: [testing, reactor-netty, spring-boot]
related: [gotchas/reactor-resource-factory-stop-disposes-global-http-resources]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/StartupFailureTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderLifecycleTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderStartupTest.kt
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/RevocationLagTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamPropertiesTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/StartedContextIsolationTest.kt
  - gateway-app/src/testFixtures/kotlin/io/github/pdroacordi/llmgateway/gateway/app/OwnReactorResources.kt
---

## Summary

A test that fully starts `GatewayApplication` and closes it disposes the JVM's global Reactor resources, so any context cached before it is left on dead event loops. `src/test` passes only because no class after `config.*` reuses an earlier context.

## Symptom

Found in the GW-04 review, while ruling on an earlier gate failure that hung cached contexts. `OwnReactorResources` fixed that case for `RevocationLagTest` only. Not reproduced by a run: the mechanism comes from the bytecode, the order from `gateway-app/build/test-results/test`. Whether this was the cause of that earlier failure is likely, not proven.

## Root cause

See the [gotcha](../gotchas/reactor-resource-factory-stop-disposes-global-http-resources.md). The full start and close sites are `StartupFailureTest.kt:138` (new in GW-04), `ProviderLifecycleTest.kt:15` and `ProviderStartupTest.kt:36,46,64,80`. Classes run by package: `chat.*`, `config.*`, `error.*`, `request.*`. The `error.*` and `request.*` classes import `ProbeController` with a set no earlier class uses, so their contexts are new. Any later class that shares a context key with an earlier one gets a dead server. The `integrationTest` suite has one such site, and it is guarded.

## Fix

Fixed in GW-04 (Roadmap: GW-04). `isolatedGateway()` in `gateway-app`'s `testFixtures` carries `OwnReactorResources`; `StartupFailureTest`, `ProviderLifecycleTest`, `ProviderStartupTest`, `ServerStreamPropertiesTest` and `RevocationLagTest` start through it.

## Prevention

One helper as the only way to start a context in a test, setting own resources. `StartedContextIsolationTest` fails if the helper stops doing so; `RevocationLagTest`'s last chat fails if a close strands a provider call.

## Revision (2026-11-04)

The hang is reproduced, and the claim about class order is corrected. With the guard removed, `RevocationLagTest`'s chat after the replica closed timed out at 30 s; with it, the test passes. So this was very likely the cause of the earlier gate failure, which hung the same way; that run itself cannot be replayed.

`src/test` was not at risk as the summary says. Reordered to `AuthRouteTest`, `ProviderStartupTest`, `ChatRouteTest`, unguarded, the suite stayed green: `@SpringBootTest` servers are off the global resources, and those contexts use a fake port, not the adapter ([gotcha](../gotchas/reactor-resource-factory-stop-disposes-global-http-resources.md)). `mock-provider`'s `ShutdownIntegrationTest` has the same shape; it is outside GW-04, was not changed, and is FND-06.
