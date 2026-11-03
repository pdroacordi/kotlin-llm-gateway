---
id: gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally
type: gotcha
title: A WebFlux error after commit is logged by Reactor Netty with its suppressed checkpoint, or ends the response normally if it looks like a disconnect
status: active
tags: [spring, webflux, reactor-netty, errors, logging]
related: [bugs/committed-response-error-logged-twice-with-query-string, bugs/error-mapper-disconnect-check-turns-errors-into-empty-200, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query, gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/CommittedResponseAbort.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/CommittedResponseIntegrationTest.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorWebExceptionHandler.kt
---

## Summary

With Spring Framework 7.0.10 and Reactor Netty 1.3.8, what a `WebExceptionHandler` returns after commit decides two things below WebFlux. `Mono.error(ex)` is logged by `reactor.netty.http.server.HttpServerOperations` with the whole throwable, suppressed checkpoint and raw query included. If `DisconnectedClientHelper` accepts `ex`, `HttpWebHandlerAdapter` swallows it and the response ends normally instead of being aborted.

## Details

Observed on a real `HttpServer` with `WebHttpHandlerBuilder` and `ReactorHttpHandlerAdapter`; the handler flushes one chunk and then fails, and the exception handler returns `Mono.error(ex)`:

| Failure after commit | Netty log | Client |
|---|---|---|
| `IllegalStateException("after commit")` | ERROR `Error finishing response. Closing connection`, printing `*__checkpoint ⇢ HTTP GET "/x?secret-query=abc123"` | premature close |
| `IllegalStateException("adapter", IOException("Connection reset by peer"))` | none | `200`, clean end of body |
| stackless `RuntimeException("aborted", null, false, false)` from the handler | ERROR line with no suppressed section and no query | premature close |

Setting `HttpWebHandlerAdapter` to `off` does not affect the Netty line. A test that drives the `HttpHandler` bean with a mock exchange sees neither behaviour, because Netty is not involved.

## How to handle it

After logging, return a stackless exception of your own instead of the original: no checkpoint for Netty to print, no phrase for the helper to match, and the connection is still aborted. `MockConnectionAbort` (`mock-provider/.../error/MockConnectionAbort.kt:8`) already has that shape for intentional aborts. Prove a committed-response rule with a test on a real port. Done in `GatewayErrorWebExceptionHandler.kt:50` (`CommittedResponseAbort`) and `MockErrorWebExceptionHandler.kt:55`.
