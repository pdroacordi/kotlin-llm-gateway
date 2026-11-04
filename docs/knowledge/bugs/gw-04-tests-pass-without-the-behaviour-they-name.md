---
id: bugs/gw-04-tests-pass-without-the-behaviour-they-name
type: bug
title: Three GW-04 tests stay green with the behaviour in their name removed
status: active
severity: low
tags: [testing, auth, cache]
related: []
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/RevocationLagTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/TeamContextPropagationTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/TeamStoreWarmupTest.kt
---

## Summary

The implementer reported these suites as never red. Reading them shows three tests that cannot fail on what they name. Production is not affected.

## Symptom

Found by reading, in the GW-04 review; no mutant was run.

- `RevocationLagTest.kt:75`, "a revoke holds at once here": before the revoke the key is used only on the other replica (`:71`), so this context has no cache entry and reads the row. Removing `known.invalidate` from `CachingTeamStore.revokeKey` stays green here; `TeamKeysEndToEndTest` and `CachingTeamStoreTest` do cover it. The test also never checks that the other replica still answers 200 after the revoke, so it would pass with no cache at all.
- `TeamContextPropagationTest.kt:109`, "no team is in the context of a request outside v1": it asserts only that health is 200, and reads no context.
- `TeamStoreWarmupTest`, "one lookup runs before the server takes requests": it calls the method directly, so removing the `teamStoreWarmup` bean stays green, and no ordering is checked.

## Root cause

Each test asserts a neighbouring fact: a result that holds with or without the cache, a status, or a direct call.

## Fix

Fixed in GW-04 (Roadmap: GW-04). Each test now fails under a one-line mutant, run and reverted:

| Test | Mutant | Red |
|---|---|---|
| `RevocationLagTest` | no `known.invalidate` in `revokeKey` | expected 401, was 200 here |
| `RevocationLagTest` | no cache hit in `findKey` | the other replica answered 401, expected 200 |
| `TeamContextPropagationTest` | a `TeamContext` on admin and open paths | `team` present on both probes |
| `TeamStoreWarmupTest` | no `teamStoreWarmup` bean | expected 1 lookup at server start, was 0 |
| `TeamStoreWarmupTest` | the warm-up deferred past the start | expected 1, was 0 |

## Prevention

For a security behaviour, name in the spec's section 8 the one-line mutant that turns the test red.
