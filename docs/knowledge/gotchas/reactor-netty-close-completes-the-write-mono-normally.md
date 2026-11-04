---
id: gotchas/reactor-netty-close-completes-the-write-mono-normally
type: gotcha
title: A client close mid-write completes the writeAndFlushWith Mono normally, with no error, and cancels the source
status: active
tags: [reactor-netty, webflux, cancellation, streaming]
related: [bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running, gotchas/spring-channel-send-operator-keeps-source-when-the-write-fails-at-once, gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
---

## Summary

On Reactor Netty 1.3.8 and Netty 4.2.19, a close during `sendGroups` does not fail the write. `MonoSendMany` listens on `closeFuture`: it cancels the source and calls `onComplete`. So `doOnSuccess` fires for a client that went away, and `doOnError` and `onErrorResume` see nothing.

## Details

Probe on a real `HttpServer`, `writeAndFlushWith` with `doOnError`, `doOnSuccess` and `doOnCancel` on it, three rounds each on Windows:

| Client | Signals on the Mono | Flow |
|---|---|---|
| FIN after the first frame | complete, then cancel | cancelled |
| RST after the first frame, slow or fast writes | complete, then cancel | cancelled |
| `WriteTimeoutHandler` closes a stalled client | complete, then cancel | cancelled |
| FIN before the first frame | cancel only, from `ChannelOperations.terminate` | cancelled |

The stack of the complete is `CloseFuture.setClosed`, then `SendManyInner.operationComplete` (`MonoSendMany.java:278`), then `MonoIgnoreThen`, then `ChannelSendOperator`. It wins over a failed write, because `AbstractUnsafe.close` runs `doClose0` before `failFlushed`, and `operationComplete` and `tryFailure` both go through one `Operators.terminate`. An `IOException` from a flush closes the channel first, so it ends the same way; `tryFailure` reaches `onError` only while the channel stays open.

## How to handle it

Never read completion of the write as delivery. Detect the end of a stream in the source: the flow's `finally`, as `ChatCompletionService.stream` does. An `onErrorResume` for disconnects on this Mono catches only `AbortedException.beforeSend`, from a write that started on a closed channel.
