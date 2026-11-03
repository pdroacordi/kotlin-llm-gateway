---
id: gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain
type: gotcha
title: DisconnectedClientHelper calls any exception whose cause chain mentions "broken pipe" or "connection reset by peer" a client disconnect
status: active
tags: [spring, webflux, errors, disconnect]
related: [bugs/error-mapper-disconnect-check-turns-errors-into-empty-200]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandlerTest.kt
---

## Summary

In Spring Framework 7.0.10, `org.springframework.web.util.DisconnectedClientHelper.isClientDisconnectedException(ex)` walks the whole cause chain. It returns `true` when any message, lowercased, contains one of its phrases, or when any cause has a known disconnect class name. It never looks at the connection.

## Details

Observed 2026-11-03 against `spring-web-7.0.10.jar`:

| Exception | Result |
|---|---|
| `ServerWebInputException(..., DecodingException("... from String \"connection reset by peer\" ..."))` | `true` |
| `IllegalStateException("adapter", IOException("Broken pipe"))` | `true` |
| `IllegalStateException("x")` | `false` |

A request-decoding error that quotes client text, or an upstream socket error, is therefore indistinguishable from a client that went away. Spring's own `HttpWebHandlerAdapter.handleUnresolvedError` uses the helper only to choose how to log.

## How to handle it

Use the helper to pick a log level, never to decide whether to write a response. Before commit, always write the error body; a write to a closed connection fails harmlessly. Fix pending in `GatewayErrorWebExceptionHandler.kt:47-50` ([bug](../bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md)).

## Revision (2026-11-03)

Fixed in FND-04. `GatewayErrorWebExceptionHandler.kt:48-49` keeps the helper's answer as `looksDisconnected` and always writes the envelope before commit; the answer only lowers the log level (`:53-57`, `:125`). At `:78` the helper is applied to the failure of the envelope write itself: a write failure it accepts is logged at DEBUG and completes, any other goes to the constant 500 fallback. The line numbers in "How to handle it" refer to the file before the fix. Guarded by `GatewayErrorWebExceptionHandlerTest:92` and `:103`.
