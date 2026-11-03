---
id: bugs/mock-scenario-times-expanded-into-memory
type: bug
title: A scenario step's `times` is expanded into one list element per request, so one admin call with a large `times` exhausts the mock's heap
status: active
severity: medium
tags: [mock-provider, admin-api, validation, memory]
related: [systems/mock-provider]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStore.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStoreTest.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJsonTest.kt
---

## Summary

`PUT /__mock/scenarios/{model}` accepts any `times` from 1 to 2^31-1, and `ScenarioStore` allocates one `Taken` object per unit of `times`. A typo such as `"times": 100000000` allocates gigabytes on the event loop. With a default container heap the allocation can succeed and stay pinned until the next reset.

## Symptom

Found in the FND-03 review (2026-11-03), against the boot jar with `-Xmx256m`:

- `{"steps":[{"times":2147483647}]}`: 500, ERROR `OutOfMemoryError: Requested array size exceeds VM limit` at `ArrayList.<init>`.
- `{"steps":[{"times":50000000}]}`: 500 after 0.44 s, ERROR `OutOfMemoryError: Java heap space` at `ScenarioStore$Entry.<init>(ScenarioStore.kt:103)`.

The process survived both at 256 MiB. A larger heap would let the second allocation succeed.

## Root cause

`ScenarioJson.step` accepted `times` in `1..Int.MAX_VALUE` (`ScenarioJson.kt:54` before the fix). `ScenarioStore.Entry` expanded the steps eagerly: `scenario.steps.flatMapIndexed { index, step -> List(step.times) { Taken(index, step.behaviour) } }` (`ScenarioStore.kt:102-103` before the fix). The sum over steps can also exceed `Int.MAX_VALUE`.

## Fix

Fixed in FND-03, both ways:

- `ScenarioStore.Entry` (`mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStore.kt:98-121`) keeps the steps unexpanded: `ends` is a `LongArray` of the cumulative `times` (`:102-106`), the cursor is an `AtomicLong` capped at the total by `getAndUpdate` (`:112`), and a binary search finds the step (`:115-116`). Memory is one `Long` per step whatever `times` is, and totals beyond `Int.MAX_VALUE` are counted.
- `ScenarioJson` caps a scenario at 1000 steps (`mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt:37-39`) and `times` at 1 000 000 (`:56`; constants at `:202-203`), each a 400 naming `steps` or `steps[i].times`.

Commit: the FND-03 commit (`Roadmap: FND-03`).

## Prevention

Added for FND-03:

- `ScenarioStoreTest:131` "huge times are not expanded into memory, and totals beyond Int are counted" (two steps of `Int.MAX_VALUE`).
- `ScenarioJsonTest:94` (1000 steps of `times` 1 000 000 are accepted), `:101` (1001 steps is a 400 naming `steps`), `:124` and `:125` (`times` 1 000 001 and 2147483647 are 400s naming `steps[0].times`).

Review rule, kept: a value from an admin or request body that sizes an allocation needs an upper bound, and a test at that bound (the systems entry lists the mock's limits).
