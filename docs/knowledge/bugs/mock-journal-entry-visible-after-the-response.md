---
id: bugs/mock-journal-entry-visible-after-the-response
type: bug
title: A read of /__mock/stats or /__mock/requests right after a response could miss that request's entry
status: active
severity: medium
tags: [mock-provider, journal, testing, race]
related: [systems/mock-provider]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/AdminHandlers.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/RequestJournal.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/ArrivalWebFilter.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/JournalReadWaitIntegrationTest.kt
---

## Summary

The mock writes a request's journal entry after the response has reached the client, so an immediate read of the journal could come back without it. About 30 test reads, every future consumer of `SharedMockProvider` and the smoke runs rely on reading their own write.

## Symptom

Found by the first Linux CI run (2026-11-03, HEAD 0eeaf7d), never on Windows: `AdminApiIntegrationTest` "stats has the documented members" failed with `PathNotFoundException: No results for path: $['counts'][0]['outcomes']`, because `counts` was still empty.

## Root cause

`ArrivalWebFilter` records the entry when the filter chain ends (`record.leave()` in its `finally`, then `RequestJournal.complete`). That runs after the body is written, so the client can have the response first. Nothing ordered the admin read after it.

## Fix

Fixed in the CI fix change (`Roadmap: FND-03`). `AdminHandlers.awaitJournalSettled` (`AdminHandlers.kt:105`) makes `GET /__mock/stats` and `GET /__mock/requests` (`:63`, `:71`) wait until `inFlight` is 0, polling every 1 ms through `awaitDelay`, so the wait is non-blocking and runs on a thread BlockHound watches. `complete` decrements `inFlight` last, so 0 means every entry is visible. The wait stops at `mock.journal.read-wait` (250 ms) and the read then answers with what is there: a held request delays a read by that bound, and polls for `inFlight != 0` still work. A request that finished beside a held one is covered too, because its entry lands within the bound.

## Prevention

`JournalReadWaitIntegrationTest` forces the race on any machine: it holds an arrival open in the real journal and completes it 100 ms later, which is the state a client can see. All three tests were red before the fix, on Windows, where the race had never shown (`inFlight` 1, and an empty `data`). Review rule: when a write happens after the response, say what a reader of that write may assume, and test it with the write held back.
