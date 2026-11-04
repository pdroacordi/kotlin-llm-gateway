---
id: bugs/held-thinking-block-grows-past-its-limit-until-it-stops
type: bug
title: A thinking block still open on a stream is held with no limit, because HeldThinking checks the size only at content_block_stop
status: active
severity: low
tags: [anthropic, streaming, memory]
related: [systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/anthropic/HeldThinking.kt
---

## Summary

`maxBytes` bounds finished blocks only. An open block's `StringBuilder`s grow on every delta, so one long block can hold far more than `max-event-size` per stream, and is dropped only when it stops.

## Symptom

Found in the GW-03 review, by reading. GW-03 section 5 says held blocks over `max-event-size` are dropped. Clients cannot ask for summarized thinking today, so real blocks are mostly a signature, which limits the exposure; a model that returns thinking text, or a faulty upstream, is bounded only by `max_tokens` or `total-timeout`. `held thinking over the limit` tests a redacted block, the path that never uses an open block.

## Root cause

`appendText` and `appendSignature` in `HeldThinking.kt` append without counting; the check is in `hold`, reached from `stop`.

## Fix

Fixed in GW-03 (Roadmap: GW-03). `start`, `appendText`, `appendSignature` and `hold` in `HeldThinking.kt` all count through `fits` (`HeldThinking.kt:52`), so what is held, open blocks included, is checked on every append. Past the limit it clears held and open blocks with one WARN, and later deltas are ignored.

## Prevention

`AnthropicStreamDecoderTest`: `an open thinking block past the limit drops all held blocks on append, with no stop`, on both `thinking_delta` and `signature_delta`. Not run over HTTP: the smoke's report lists this among what it did not exercise.
