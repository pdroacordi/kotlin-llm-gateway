---
id: bugs/usage-count-overflow-gives-500-on-a-paid-answer
type: bug
title: Provider token counts whose sum overflows a Long give a 500 and lose a paid answer
status: active
severity: low
tags: [adapters, usage, openai]
related: [systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/Usage.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiResponseReader.kt
---

## Summary

`usage` with `prompt_tokens` 9223372036854775807 and `completion_tokens` 1 passes the reader, then `Usage.totalTokens` overflows to a negative value and `TokenCount.init` throws `IllegalArgumentException`. The spec's rule is that a bad count counts as omitted, so a paid answer is not thrown away.

## Symptom

Found in the GW-01 review, by the real reader and writer in a probe: `IllegalArgumentException: A token count cannot be negative.` from `ChatResponseWriter`, which is a 500 `internal_error` with an ERROR trace.

## Root cause

`TokenCount.plus` (`Usage.kt:13`) uses plain `Long` addition. `OpenAiResponseReader.usage` (`OpenAiResponseReader.kt:71-83`) checks each count, not their sum. `ChatResponseWriter.kt:57` reads `totalTokens` after the service has returned.

## Fix

`OpenAiResponseReader.kt:79` treats `usage` as omitted when the two counts do not fit a `Long` together, so the answer survives with an estimate. `TokenCount.plus` is `Math.addExact` (`Usage.kt:13`), so a later sum fails with the right exception, not a negative count.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`OpenAiResponseReaderTest` malformed-usage row with 9223372036854775807 and 1, and `TokenCountTest` "a sum past Long says so"; both red first.
