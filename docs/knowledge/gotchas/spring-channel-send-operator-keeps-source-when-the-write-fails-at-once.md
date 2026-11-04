---
id: gotchas/spring-channel-send-operator-keeps-source-when-the-write-fails-at-once
type: gotcha
title: ChannelSendOperator leaves its source subscribed when the write function fails without subscribing to it
status: active
tags: [spring, webflux, reactor, cancellation]
related: [bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running, gotchas/reactor-netty-close-completes-the-write-mono-normally]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
---

## Summary

In Spring Framework 7.0.10, `ChannelSendOperator` passes a failed write function's error downstream and does not cancel the source that gave it the first item. A later cancel from downstream does not reach it either, once an `onErrorResume` has replaced the failed upstream.

## Details

Reproduction: `ChannelSendOperator(flow.asFlux(), { Mono.error(AbortedException.beforeSend()) }).onErrorResume { Mono.empty() }`, subscribed, then disposed after 600 ms. `onErrorResume` runs and the Mono completes, and the flow's `onCompletion` never runs. The flow stays suspended at its first `emit`, with demand used up.

The real trigger is `ChannelOperations.send`, which returns that `Mono.error` when `channel().isActive()` is false. Once the write function has subscribed to the barrier, its own failure or cancel does reach the source, so this applies only to a failure before that.

## How to handle it

Do not rely on the operator to end a source that owns resources. Tie the source to the end of the returned Mono: `source.takeUntilOther(sink.asMono())` with `doFinally { sink.tryEmitEmpty() }` on the result. Done in `SseBodyInserter` ([bug](../bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running.md)).
