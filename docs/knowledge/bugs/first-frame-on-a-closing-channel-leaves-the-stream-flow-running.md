---
id: bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running
type: bug
title: A first frame sent while the client's channel is closing swallows the error and never cancels the flow, so no end-of-stream report fires and the provider call runs on
status: active
severity: medium
tags: [streaming, sse, webflux, reactor-netty, cancellation, coroutines]
related: [systems/provider-adapters, gotchas/spring-channel-send-operator-keeps-source-when-the-write-fails-at-once, gotchas/reactor-netty-close-completes-the-write-mono-normally, patterns/cold-event-flow-commits-at-first-frame]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatCompletionService.kt
---

## Summary

If the first frame reaches `sendGroups` after the channel went inactive but before Reactor Netty's `terminate` cancels the chain, the write fails at once with `AbortedException`, `SseBodyInserter` resumes empty, and nothing cancels the flow. It stays suspended at its first `emit`: `finally` never runs, `onEnd` never fires, and the upstream call is not cancelled.

## Symptom

Found in the GW-02 review. A probe on a real `HttpServer` with the built `SseBodyInserter` releases the first frame from a `closeFuture` listener, which runs in exactly that window. Output: `closeFuture listener: isActive=false`, `first frame goes out`, `inserter Mono completed; committed=true`, `inserter Mono cancelled`. The flow's `onCompletion` never ran, 1.5 s later included.

In the gateway the first frame runs on a client event loop and the close on the server's, so the window is real, and short: the time between `doClose` and `channelInactive` on the server loop. When it hits:

- no `Stream cancelled` line, against contract 12 of `systems/provider-adapters`, and later no release of RL-02's reservation and no COST-02 event;
- the provider keeps generating until demand runs out, and the upstream connection is held until `idle-timeout` (60 s), against "client disconnect cancels the provider call".

## Root cause

`ChannelOperations.send` returns `Mono.error(AbortedException.beforeSend())` when `channel().isActive()` is false. That is the write function of Spring's `ChannelSendOperator`, which fails without subscribing to the barrier, and the operator then signals the error downstream and leaves its source subscribed. `SseBodyInserter.kt:35-37` turns the error into `Mono.empty()`. The later cancel from `terminate` stops at `onErrorResume`, which no longer holds the failed upstream. Letting the error through to the mapper would not help: the source would stay subscribed there too.

This is the only path found into that `onErrorResume`. A client FIN, RST or `WriteTimeoutHandler` close mid-stream completes the Mono normally ([gotcha](../gotchas/reactor-netty-close-completes-the-write-mono-normally.md)).

## Fix

Fixed in the GW-02 commit (Roadmap: GW-02). `SseBodyInserter.kt:26-27` ties the source to a sink with `takeUntilOther`, and `doFinally` at `:40` fires it, so the flow ends cancelled whatever `ChannelSendOperator` does, and `onEnd` reports it.

## Prevention

`ChatStreamClosingChannelTest`: a real port, the first frame held on a gate that a `closeFuture` listener releases; one `Stream cancelled` line. Deterministic, and red before the fix (no line within 5 s).
