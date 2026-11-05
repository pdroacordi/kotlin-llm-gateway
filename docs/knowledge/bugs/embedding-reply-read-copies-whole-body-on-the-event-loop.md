---
id: bugs/embedding-reply-read-copies-whole-body-on-the-event-loop
type: bug
title: An embedding reply of up to 256 MB is allocated and copied to a byte array on the event loop, before the lane
status: active
severity: medium
tags: [embeddings, event-loop, webclient, gw-05]
related: [bugs/embedding-reply-write-copies-whole-reply-on-the-event-loop, bugs/embedding-error-body-tree-read-twice-on-the-event-loop]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/upstream/UpstreamClient.kt
---

## Summary

ADR-GW-05-012 names one body copy left on the loop, the request's, at most 10 MB. The reply has one too: `ByteArrayDecoder` allocates N and copies N on the `reactor-http-*` thread that delivers the last buffer, before `lane.run` is reached.

## Symptom

Found in review; not measured. A 198 MiB allocation, zeroed and humongous under G1, then a copy of that size, holds a loop shared with chat, against the 15 ms overhead budget ADR-GW-05-010 protects.

## Root cause

`UpstreamClient.kt:138`: `toEntity(ByteArray::class.java)` decodes through `AbstractDataBufferDecoder.decodeToMono`, which is `DataBufferUtils.join(...).map(decode)` with no scheduler (bytecode of spring-core 7.0.10). The adapter resumes only after that map.

## Fix

Fixed in GW-05 (Roadmap: GW-05). `readOnLane` (`UpstreamClient.kt:50`) decodes to the joined `DataBuffer`, which costs no copy, and `CpuLane.copy` (`CpuLane.kt:37`) makes the array on the lane, inside `exchangeToMono`, so no gap lets a cancel drop a buffer. Exactly one of the copy and a cancel takes the buffer and releases it. Chat's read is unchanged (`readOnEventLoop`).

Red before the fix: the event loops allocated 8,784,232 bytes for a reply of 8 MiB (`OpenAiEmbeddingAdapterFailureTest` "a large reply is copied to an array on the lane, not on an event loop"; the test allows under half the reply). `CpuLaneTest` holds the release on a copy, a cancel and a refusal.

## Prevention

`EmbeddingThreadTest` could assert the thread that allocates the body. Add a 198 MiB reply to smoke 10, which has only the 10 MB request.
