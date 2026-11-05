---
id: bugs/embedding-unreadable-2xx-log-has-no-test
type: bug
title: After interpret was split, no test fails if the unreadable-2xx log line is removed or builds the tree
status: active
severity: low
tags: [embeddings, tests, gw-05]
related: [bugs/gw-04-tests-pass-without-the-behaviour-they-name]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
---

## Summary

No test sees `OpenAiEmbeddingAdapter.kt:98`, so the rule that an unreadable 2xx logs `ProviderErrorFields.NONE` is unproved, and so is the WARN itself.

## Symptom

Found in review by reading the tests; the mutants were not run. These survive by inspection: `NONE` replaced with `classifier.errorFields(body)`; `calls.failed` removed at `:83` or `:98`; `HttpHeaders.EMPTY` passed at `:85`, since no adapter test sends `Retry-After`. `a malformed 200 is UpstreamError` asserts the result and the journal only, and the only log assertion on this path in the embedding tests waits for `Provider call answered`. These would fail: the scan made inline (`EmbeddingThreadTest`), and the null check in `success` removed (`a malformed 200 is UpstreamError`).

## Root cause

The adapter has only integration tests, and none captures output.

## Fix

Fixed in GW-05 (Roadmap: GW-05). `OpenAiEmbeddingAdapterLogTest` holds the line of an unreadable 2xx, with no fields whatever error object it holds, the line of a non-2xx, and `Retry-After` on a 429 and a 503.

## Prevention

Rerun the breaks after a refactor of the code they cover.
