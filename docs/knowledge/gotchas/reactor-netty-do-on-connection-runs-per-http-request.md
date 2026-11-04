---
id: gotchas/reactor-netty-do-on-connection-runs-per-http-request
type: gotcha
title: On an HTTP/1.1 server doOnConnection runs per request, and a handler added there is removed when that request ends
status: active
tags: [reactor-netty, netty, pipeline, keep-alive, pipelining]
related: [systems/provider-adapters, bugs/client-stall-handler-misses-a-stall-while-the-queue-grows]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
---

## Summary

On Reactor Netty 1.3.8, `HttpServer.doOnConnection` is not once per TCP connection. It runs on `State.CONFIGURED`, which `HttpTrafficHandler` emits for each request, and `Connection.addHandlerLast` removes what it added when that request terminates. So a handler installed there has a request's lifetime, not a channel's.

## Details

From the 1.3.8 sources:

- `ServerTransportConfig.java:266`: the callback runs on `CONFIGURED`.
- `HttpTrafficHandler.java:287` and `:692`: `CONFIGURED` is emitted per request, on the direct path and when draining pipelined requests.
- `ReactorNetty.java:377`: a name already in the pipeline is skipped. `:401` and `:485`: otherwise the handler is removed on `context.onTerminate()`, and `context` is the request's `HttpServerOperations`.
- `HttpServerOperations.java` `onOutboundComplete` terminates from the listener on the last write, so the handler stays until the kernel has taken the last byte.
- `terminate` runs before the next pipelined request is drained, so the skip at `:377` does not leave that request without a handler.

Measured through `ClientStallHandler` on a real socket, by the identity of the pipeline entry at each `doOnConnection` call: three keep-alive requests on channel `16fef208` saw three instances, and two pipelined requests on channel `101dd19d` saw two. A stalled pipelined second request was still cut, in 1309 ms at a 1 s timeout.

## How to handle it

- Treat per-handler state as per-request. Anything that must span a connection belongs in `doOnChannelInit`.
- A handler installed here costs nothing between requests: no timer runs on an idle kept-alive connection.
- `ClientStallHandler` works either way, because its state only needs to last one response; its KDoc (`ClientStallHandler.kt:13`) says "One instance per channel", which is wrong.

## Revision (2026-11-04)

The KDoc named above is fixed: it says one instance per HTTP request.
