---
id: patterns/copy-and-release-pooled-buffers-before-a-flow
type: pattern
title: Copy and release a pooled buffer in the same synchronous step, before it reaches a Flow, because a Flow's channel cannot release what it drops
status: active
tags: [reactor, netty, coroutines, streaming, memory, cancellation]
related: [patterns/cold-event-flow-commits-at-first-frame, patterns/raw-sse-frames-flushed-per-event, systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatStream.kt
---

## Summary

A reactive body delivers reference-counted buffers that the consumer must release. Crossing into a Kotlin `Flow` puts them in a channel, and on cancellation nothing releases what is in it. Turn each buffer into plain bytes in the operator that receives it, and let only plain objects reach a queue.

## Details

Canonical use: GW-02 spec, ADR-GW-02-008; `OpenAiChatStream.kt`, proved by `StreamBufferLeakIntegrationTest` and its deliberate violation.

```kotlin
private fun copyAndRelease(buffer: DataBuffer): ByteArray =
    try {
        ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
    } finally {
        DataBufferUtils.release(buffer)
    }

response.body(BodyExtractors.toDataBuffers()).map(::copyAndRelease)   // before asFlow
```

- `ReactorClientHttpResponse.getBody()` retains each `ByteBuf`, and its `map` hands it straight on, with no queue before ours.
- On the write side do the reverse: keep `ByteArray`s through the pipeline and wrap inside the inner `Mono`, so a cancelled queue holds arrays.
- Prove it with `ResourceLeakDetector` at `PARANOID`, many cancelled streams, forced GC and a search for `LEAK:`, plus a deliberate violation that removes the release.

## Why

- `asFlow` buffers into a `Channel` created with no `onUndeliveredElement`, and its `finally` only cancels the subscription (https://github.com/Kotlin/kotlinx.coroutines/blob/master/reactive/kotlinx-coroutines-reactive/src/ReactiveFlow.kt). Reactor's `doOnDiscard` never runs for it, because it is not a Reactor queue.
- A `try`/`finally` further down cannot help: a buffer in the channel has no code running for it at cancellation.
- The cost is one copy per network read. For token streams, reads are small, and the bytes are parsed anyway.

## Gotchas / pitfalls

- Any operator with a queue between the receipt and the copy (`publishOn`, `flatMap` with prefetch) reopens the leak.
- This fits small, frequent messages. A large-file proxy would pay for the copy, and should stay in Reactor end to end, with `doOnDiscard`.
- A leak detector can miss a leak if GC does not run, and never invents one, so a green test needs volume and the deliberate violation.
