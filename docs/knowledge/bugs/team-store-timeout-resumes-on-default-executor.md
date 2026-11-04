---
id: bugs/team-store-timeout-resumes-on-default-executor
type: bug
title: A timed-out team store lookup served its 503 on kotlinx's DefaultExecutor, unguarded by BlockHound
status: active
severity: low
tags: [postgres, coroutines, blockhound, auth]
related: [bugs/mock-serving-after-delay-runs-on-default-executor, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-infra/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/infra/postgres/PostgresTeamStore.kt
  - gateway-infra/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/infra/postgres/PostgresTeamStoreDeadlineTest.kt
---

## Summary

`PostgresTeamStore.guarded` used `withTimeoutOrNull`. Under `CoWebFilter`'s `Unconfined`, the timer's cancel resumed the request on `kotlinx.coroutines.DefaultExecutor`, so everything after a store timeout ran on one thread BlockHound does not watch.

## Symptom

Found in the GW-04 review. `PostgresTeamStoreDeadlineTest` failed with `resumed on kotlinx.coroutines.DefaultExecutor @coroutine#1`.

## Root cause

The same as the [mock's](mock-serving-after-delay-runs-on-default-executor.md): `Unconfined` has no timer. Only the timed-out path was affected; a lookup that answers resumes on the driver's thread.

A deadline shorter than the cold class loading before the first suspension hides this. The timer fires while the caller is still in the block, the first suspension sees the cancel, and the call ends on the caller's thread. The test's first version, at 50 ms, showed `Test worker` for that reason.

## Fix

`guarded` wraps the operation in `mono(...)` with `Mono.timeout`, as `UpstreamClient.post` does. The timer runs on Reactor's `parallel` scheduler, whose threads BlockHound watches, and the timeout cancels the query.

## Prevention

`PostgresTeamStoreDeadlineTest` (no Docker) and the held-query case of `PostgresTeamStoreFailureIntegrationTest` assert the thread name. Any deadline on a path under `Unconfined` uses a Reactor timer.
