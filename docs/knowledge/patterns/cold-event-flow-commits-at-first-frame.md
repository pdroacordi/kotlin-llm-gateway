---
id: patterns/cold-event-flow-commits-at-first-frame
type: pattern
title: A stream is a cold flow of sealed events, and the response commits at the first frame, so an earlier failure is still a status
status: active
tags: [sse, streaming, webflux, coroutines, errors, provider]
related: [patterns/raw-sse-frames-flushed-per-event, patterns/one-error-funnel, patterns/copy-and-release-pooled-buffers-before-a-flow, gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatStream.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/StreamTally.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamFrames.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
---

## Summary

A streamed response must fail with a status until its first byte, and in-band after it, and one thing must own the upstream connection. The port returns a cold `Flow` of sealed events ending in one terminal event. Nothing runs until the response body subscribes, and the first emitted frame commits. A failure raised before that frame reaches the normal error mapper.

## Details

Canonical use: GW-02 spec, ADR-GW-02-001; `ChatStreamRouteTest` pins the commit point.

- **Port:** `fun stream(call): Flow<ProviderStreamEvent>`: `Chunk`s, then exactly one of `Completed` or `Failed`. An expected failure is a value at any position, never an exception.
- **Named commit point:** a pure state machine emits `Started` right before the first forwarded chunk. Fallback, status and headers can change only before `Started`.
- **Handler:** returns `ServerResponse.ok().body(inserter)` at once, with no headers on the builder.
- **Frames:** at `Started`, set the headers on the still-uncommitted response. `Failed` before it: throw the edge exception. `Failed` after it: emit the in-band frame and end normally.
- **Why a throw commits nothing:** `writeAndFlushWith` wraps its body in Spring's `ChannelSendOperator`, which waits for the first signal: "If the first emission is an error, the write function is bypassed". The error then travels to the one mapper.
- **Report:** `finally` around `collect` calls a non-suspending `onEnd(summary)`, once. A terminal event beats a late cancellation.

## Why

- `suspend fun open(): Opened(flow) | Failed` reads better, but returns with a connection open and no collector, and a `coRouter` handler returns before the body is written. Something would have to own the connection past the handler: a longer-lived scope or a bridge that leaks when the flow is never collected. A cold flow has one owner, the response subscription, so cancellation needs no code.
- A fallback chain becomes a loop inside the flow.
- Public basis: `ChannelSendOperator` and `AbstractServerHttpResponse` in Spring Framework 7.0.x (https://github.com/spring-projects/spring-framework/blob/7.0.x/spring-web/src/main/java/org/springframework/http/server/reactive/ChannelSendOperator.java). Applying it to a provider stream is this project's design.

## Gotchas / pitfalls

- Headers on the `ServerResponse` builder are copied before the inserter runs, and an error clears only content headers, so `cache-control` or a success-only header would survive onto the error. Set them at `Started`.
- A flow that completes with no element does not bypass the write: it commits an empty 200. A terminal `Failed` must always come first.
- `ChannelSendOperator` is a Spring internal. Keep a route test that fails first and asserts status and headers.
- A client sees no byte until the first chunk. A comment line sent early would commit the status.
- `onEnd` can run in a cancelled coroutine: anything suspending there needs `withContext(NonCancellable)`.
