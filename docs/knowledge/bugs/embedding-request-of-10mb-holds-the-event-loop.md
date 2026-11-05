---
id: bugs/embedding-request-of-10mb-holds-the-event-loop
type: bug
title: A 10 MB embeddings request holds an event loop for about 400 ms and allocates about 720 MB
status: active
severity: medium
tags: [embeddings, event-loop, memory, jackson, gw-05]
related: [systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingRequestReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
---

## Summary

The tree read, the typing and the rebuild of the upstream body all run inline on `reactor-http-*`. `[[1],[1],...]` is the costly form, and the provider then refuses it for having over 2048 items, so the call does the gateway harm and the caller no good.

## Symptom

Found in review. A standalone equivalent of the three steps on Jackson 3.1.7, warm JVM, 2.4 million `[1]` items, 9.6 MB: 316 to 474 ms and about 720 MB allocated per request (parse 81 to 187 ms, typing 97 to 197 ms, write 67 to 172 ms). Not measured through the app. Every connection on that loop waits meanwhile, and no limit exists until RL-02.

## Root cause

`EmbeddingHandler.kt:27` and `OpenAiEmbeddingAdapter.kt:57`. ADR-GW-05-009's "the body limit bounds the read" bounds bytes, not time. Chat pays the parse too, but not this typing and rebuild.

## Fix

Fixed in GW-05 (Roadmap: GW-05), on `CpuLane` rather than `parallel` (ADR-GW-05-012): the parse and typing run in `EmbeddingHandler.typed` and the upstream write in `OpenAiEmbeddingAdapter.exchange`, both through `lane.run`. `awaitBody` still joins on the loop, one copy of at most 10 MB. `EmbeddingThreadTest` holds it.

## Prevention

Extend `EmbeddingThreadTest` to the request path, and add this body to smoke 10.
