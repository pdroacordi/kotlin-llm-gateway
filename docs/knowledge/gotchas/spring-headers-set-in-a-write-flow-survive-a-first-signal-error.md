---
id: gotchas/spring-headers-set-in-a-write-flow-survive-a-first-signal-error
type: gotcha
title: Headers set on the response before a first-signal error survive onto the error response, except the content headers
status: active
tags: [spring, webflux, streaming, errors]
related: [patterns/cold-event-flow-commits-at-first-frame, gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamFrames.kt
---

## Summary

With Spring Framework 7.0.10, when the source of `writeAndFlushWith` fails as its first signal, nothing commits, and `doOnError` runs `clearContentHeaders()`. Everything else already set on the response stays.

## Details

Reproduction on a real `HttpServer`: a flow sets `Content-Type`, `Cache-Control`, `x-accel-buffering` and `x-gateway-deployment` on `response.headers`, then throws. The error handler sees `committed=false` and `Content-Type` null, sets 500 and completes. The wire shows `HTTP/1.1 500`, `Cache-Control: no-cache`, `x-accel-buffering: no`, `x-gateway-deployment: openai`. A builder's headers behave the same way, because `writeTo` copies them to the response before the inserter runs.

## How to handle it

Set stream-only headers at the last moment before the first item, in the same step that guarantees an item follows, and never on the `ServerResponse` builder. `ChatStreamFrames.kt:46-54` does so at `Started`: after it, a bug becomes a frame, so the first signal is an item. If a failure can follow the header step and still be the first signal, remove the headers in the error path.
