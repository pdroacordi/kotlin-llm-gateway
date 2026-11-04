---
id: bugs/anthropic-stream-keeps-message-start-output-tokens
type: bug
title: An Anthropic stream whose message_delta usage is missing or unreadable reports the message_start output count as provider usage
status: active
severity: medium
tags: [anthropic, streaming, usage, cost]
related: [systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/anthropic/AnthropicUsageReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/anthropic/AnthropicStreamDecoder.kt
---

## Summary

`message_start` carries a placeholder `output_tokens` (1 in the mock, whose renderer cites the documented example). If `message_delta` has no `usage`, or an unreadable `output_tokens`, that placeholder becomes the stream's `completion_tokens`, unflagged, however long the answer was.

## Symptom

Found in the GW-03 review, by trace, not run. `message_start` with `usage: {input_tokens: 10, output_tokens: 1}`, 500 tokens of text, then `message_delta` with `delta.stop_reason: end_turn` and `usage: {output_tokens: "x"}` or no `usage`, then `message_stop`. The usage chunk says `completion_tokens: 1`, and `StreamSummary` holds it as provider usage. GW-03 section 4 says an unreadable or missing `output_tokens` is omitted usage, estimated and flagged.

No test covers it: `AnthropicUsageReaderTest` feeds a good delta, the decoder's "no usage anywhere" case has no `usage` in `message_start` either, and the mock's `usage.omit` drops both.

## Root cause

`UsageCounts.updatedBy` (`AnthropicUsageReader.kt:23`) keeps the old value for any count absent or unreadable in the update, which is right for input and cache counts and wrong for output: only the delta's value is final. `AnthropicStreamDecoder.messageStart` (`AnthropicStreamDecoder.kt:103`) feeds `output_tokens` into the same field.

## Fix

Fixed in GW-03 (Roadmap: GW-03). `AnthropicStreamDecoder.messageStart` (`AnthropicStreamDecoder.kt:103`) now calls `UsageCounts.startedBy` (`AnthropicUsageReader.kt:18`), which clears `output` and `thinking` and any unreadable mark on them, so only `message_delta` can set output, and `AnthropicUsageReader.usage` gives null without one: estimated and flagged.

## Prevention

`AnthropicStreamDecoderTest`: `message_delta with no usage omits it, whatever message_start said of output`, and the same with an unreadable output count. The smoke's stub showed `message_start`'s 99 never used ([run](../runs/2026-11-04-smoke-anthropic-adapter.md), scenario 20).
