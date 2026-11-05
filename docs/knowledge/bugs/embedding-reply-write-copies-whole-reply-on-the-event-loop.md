---
id: bugs/embedding-reply-write-copies-whole-reply-on-the-event-loop
type: bug
title: An embedding reply is copied whole into a direct buffer on the event loop, which ADR-GW-05-006 and -010 do not count
status: active
severity: medium
tags: [embeddings, netty, memory, event-loop, gw-05]
related: [gotchas/netty-copies-a-heap-buffer-to-direct-on-write, gotchas/reactor-netty-send-writes-its-whole-prefetch-past-unwritable, bugs/embedding-max-connections-does-not-cap-reply-memory]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingResponseWriter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingHandler.kt
---

## Summary

ADR-GW-05-006 counts direct memory only while reading, and ADR-GW-05-010 moves the work off the loop. The write puts one heap `ByteBuffer` of N bytes on the channel, so Netty allocates N of direct memory and copies all of it on the event loop.

## Symptom

Found in review, from the bytecode of `AbstractNioByteChannel.filterOutboundMessage` in Netty 4.2.19; not measured through the app. The copy of a 198 MiB reply runs on a loop shared with chat streams, against the budget ADR-GW-05-010 protects, and the direct memory stays until the client has read it.

## Root cause

`EmbeddingResponseWriter.kt:80` returns one buffer and `EmbeddingHandler.kt:45` sends it as one message.

## Fix

Fixed in GW-05 (Roadmap: GW-05), in two steps: slices, then pacing. See the revisions.

## Prevention

Report peak direct memory beside heap in smoke 5 and 6.

## Revision (2026-11-05)

The fix, 1 MiB slices through `writeWith` (`EmbeddingResponseWriter.kt:28`, ADR-GW-05-013), is incomplete. Each write is one slice, but Reactor Netty does not stop writing while the channel is unwritable: one pass of `MonoSendMany.SendManyInner.run` writes up to 128 slices, so up to 128 MiB is copied to direct memory on the loop in one go, and a reply of at most 128 MiB is still copied whole ([gotcha](../gotchas/reactor-netty-send-writes-its-whole-prefetch-past-unwritable.md)). ADR-GW-05-013's "stops pulling while the channel is not writable" and ADR-GW-05-006's "one 1 MiB slice at a time" are false. Read from the bytecode of reactor-netty-core 1.3.8; not measured through the app.

`EmbeddingSlicedWriteTest` passes because it checks the largest single write, not how many are pending.

**Fix.** Proposed here: write each slice as its own group, as `SseBodyInserter.kt:30` does. That proved wrong; see the next revision.

**Prevention.** A test with a client that reads nothing: count bytes written at the head of the pipeline, and require about one slice beyond what the socket took. Smoke 5 and 6 report peak direct memory.

## Revision (2026-11-05, the fix)

`writeAndFlushWith` with one `Mono` a slice does not make a slice wait for the last write. `NettyOutbound.sendGroups` on reactor-netty-core 1.3.8 is one `send` of the groups flattened by `concatMap`, with a boundary marker that only asks for a flush, so it is the same `MonoSendMany`. With it in place, the new test stayed red: a client that read nothing had all 33,554,720 bytes of a 32 MiB reply written, the same as before.

**Fix.** `writeAndFlushWith` stays, and the source is paced: `ReplyWritePacer` (`ReplyWritePacer.kt:33`) holds each slice until `channelWritabilityChanged` says the channel can take more, on the channel's event loop, where `MonoSendMany` writes inline (`EmbeddingResponseWriter.kt:33`). `Content-Length` is unchanged: groups reach `send` as a `Flux`, so no full message is built, and `EmbeddingSlicedWriteTest` asserts the header and every byte. ADR-GW-05-013 and -006 are corrected.

**Prevention.** `EmbeddingSlicedWriteTest` "a client that stops reading holds at most about one slice in the channel" counts `totalPendingWriteBytes` at the head of the pipeline, at most 2 MiB of 32 MiB, and that the client took less than the reply. Peak direct memory through the app is not measured (OPS-06).
