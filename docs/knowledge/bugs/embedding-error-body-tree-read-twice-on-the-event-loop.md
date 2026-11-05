---
id: bugs/embedding-error-body-tree-read-twice-on-the-event-loop
type: bug
title: A non-2xx embeddings reply of up to 256 MB is read as a tree twice on the event loop
status: active
severity: low
tags: [embeddings, event-loop, memory, gw-05]
related: [patterns/upstream-bytes-then-pure-classification]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
---

## Summary

ADR-GW-05-002 raises the limit for every reply, errors included, to 16 times chat's. Only a 2xx leaves the loop, so a large error body from a provider or a proxy is parsed inline, twice.

## Symptom

Found in review by reading; not reproduced. It needs an oversized JSON error body, which OpenAI does not normally send.

## Root cause

`OpenAiEmbeddingAdapter.kt:83` (`errorFields`) and `:85` (`classify`, which calls `errorFields` again).

## Fix

Fixed in GW-05 (Roadmap: GW-05). `OpenAiEmbeddingAdapter.errorFields` (`OpenAiEmbeddingAdapter.kt:112`) reads a body of at most 64 KiB once, on the lane, and gives the fields to both the log and the classifier; a larger one is not read (ADR-GW-05-014).

## Prevention

An adapter test with a large 500 asserting the number of reads or the thread.
