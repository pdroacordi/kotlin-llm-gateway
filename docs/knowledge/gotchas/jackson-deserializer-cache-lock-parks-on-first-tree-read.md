---
id: gotchas/jackson-deserializer-cache-lock-parks-on-first-tree-read
type: gotcha
title: Concurrent first tree reads on a Jackson 3 mapper park on a ReentrantLock, which BlockHound fails on an event loop
status: active
tags: [jackson, blockhound, webflux]
related: [systems/provider-adapters, gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
---

## Summary

Jackson 3.1.7 builds a mapper's `JsonNode` deserializer on first use under a `ReentrantLock` in `DeserializerCache`. Two event loops doing their first read at once make one park, and BlockHound reports `jdk.internal.misc.Unsafe#park`.

## Details

Probe under BlockHound on Linux, 4 CPUs: 3000 fresh mappers, each read by 4 `Schedulers.parallel()` threads released together.

- Cold: 416 blocking calls, all `Unsafe.park` from `ReentrantLock.lock` at `DeserializerCache._createAndCacheValueDeserializer` (`DeserializerCache.java:225`).
- After one `readTree("{}")` on the same mapper: 0.

The cache belongs to the mapper, so a reader derived with `.with(...)` shares it. A single first read does not park, so a sequential test misses this. The first error envelopes, after a tree read and write, parked 0 times in 1500 rounds.

## How to handle it

Read and write one tree at startup with the mapper the request path uses: `OpenAiChatAdapter.warmUp` (`OpenAiChatAdapter.kt:78-79`), which shares Boot's `JsonMapper` with `ChatHandler`. A new mapper on an event-loop path needs the same.
