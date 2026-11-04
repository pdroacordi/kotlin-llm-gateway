---
id: gotchas/netty-write-timeout-clock-starts-in-the-queue-and-logs-no-request-id
type: gotcha
title: WriteTimeoutHandler's clock includes time queued behind earlier writes, and its exception surfaces as a FluxReceive WARN with no request ID
status: active
tags: [netty, reactor-netty, timeouts, logging, streaming]
related: [bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading, gotchas/reactor-netty-close-completes-the-write-mono-normally]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamConfiguration.kt
---

## Summary

On Netty 4.2.19 and Reactor Netty 1.3.8, `WriteTimeoutHandler` times each write from `write()` until its promise completes, not the time a socket makes no progress. When it fires, the only trace from the library is one `FluxReceive` WARN on an event loop, with no MDC.

## Details

- A client reading 16 KB every 50 ms, never pausing over 171 ms, was closed by a 2 s handler at 2.6 s, because frames queued behind each other ([bug](../bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading.md)).
- A stalled client gives `[reactor-http-nio-6] WARN reactor.netty.channel.FluxReceive -- [c5a3ff8e-1, L:... - R:...] An exception has been observed post termination, use DEBUG level to see the full stack: io.netty.handler.timeout.WriteTimeoutException`. It is the handler's `fireExceptionCaught` reaching `onInboundError` after the inbound finished. The handler then closes the channel, and the client reads what the kernel held, then EOF.
- `doOnConnection` with `addHandlerLast` holds for later requests on a kept-alive connection (`StalledClientStreamTest`).

## How to handle it

To measure a stall, use `IdleStateHandler` with `observeOutput` and close only with bytes pending, as `ClientStallHandler` does since GW-02: a first event says only that no write completed, so count from it. For `WriteTimeoutHandler` itself:

Find the request from the stream's own `Stream cancelled` line, which carries the ID. For a non-stream response nothing does: there the WARN is all there is. Treat the value as a floor on client throughput, queued bytes over the timeout, not as a stall time.
