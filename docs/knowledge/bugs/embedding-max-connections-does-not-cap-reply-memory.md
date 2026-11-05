---
id: bugs/embedding-max-connections-does-not-cap-reply-memory
type: bug
title: embeddings.max-connections does not cap reply memory, because the pool slot is free before the scan and the write
status: active
severity: high
tags: [embeddings, memory, reactor-netty, gw-05]
related: [systems/provider-adapters, gotchas/netty-copies-a-heap-buffer-to-direct-on-write]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapterConfig.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingHandler.kt
---

## Summary

ADR-GW-05-006, GW-05 section 5 ("the only cap"), `OpenAiEmbeddingAdapterConfig.kt:8` and `application.yaml` say the pool caps calls in flight, and so heap. It caps only the upstream read. Everything after that read, about 2N of heap per call, is uncapped.

## Symptom

Found in review by reading the code; not reproduced under load. Slow clients call `/v1/embeddings` with small requests that draw large replies. At 100 Mbit/s a 198 MiB reply takes 17 s to drain. Each finished read frees its slot, so the next wave starts while earlier replies are still in memory. With `max-connections: 3` on a 2 GiB heap, as the ADR advises, more than 3 replies are held at once, and an OOM takes chat down with it.

## Root cause

`exchangeToMono` completes, and the connection returns to the pool, once `toEntity` has the whole body (`UpstreamClient.post`). The scan (`OpenAiEmbeddingAdapter.kt:107`) and `EmbeddingResponseWriter.write` run after that, and the buffer lives until the client has read it (`EmbeddingHandler.kt:45`). `ClientStallHandler` cuts a stalled client, not a slow one.

## Fix

Fixed in GW-05 (Roadmap: GW-05), by a budget in bytes and not a permit: `EmbeddingAdmission` reserves a call's priced reply, and `EmbeddingReplyFilter`'s `doFinally` releases it when the response write ends (ADR-GW-05-011). `max-connections` is now documented as a cap on upstream reads only.

## Prevention

A test with a pool of 1 and a client that does not read: the second call must wait or be refused. Smoke 6 measures heap, but with clients that read at full speed.
