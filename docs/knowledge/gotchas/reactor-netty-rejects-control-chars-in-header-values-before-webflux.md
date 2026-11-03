---
id: gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux
type: gotcha
title: A control character in any request header value is rejected by Reactor Netty with an empty 400, before WebFlux runs
status: active
tags: [reactor-netty, webflux, headers, errors, logging, request-id]
related: [systems/request-context-and-errors, runs/2026-11-03-smoke-mock-provider-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextWebFilter.kt
---

## Summary

With Reactor Netty 1.3.8 (Spring Boot 4.1.2), a request whose header value holds a control character such as 0x01 or 0x7F (DEL) never reaches WebFlux. Netty's header validation answers `400` with `content-length: 0` and `connection: close`: no error envelope, no `x-request-id`. It also logs one WARN line from `reactor.netty.http.server.HttpServerOperations`.

## Details

Observed in the smoke run of 2026-11-03 ([runs/2026-11-03-smoke-mock-provider-and-errors](../runs/2026-11-03-smoke-mock-provider-and-errors.md), scenario G6c and finding 3) against `gateway-app`:

- `x-client-request-id: ab<0x7F>`, or any other header such as `x-other: ab<0x01>`, gave `400`, an empty body and `connection: close`. No `x-request-id` header was set.
- The WARN line reads `Decoding failed: … Validation failed for header 'x-client-request-id'` with the request line `GET /v1/nope?<filtered> HTTP/1.1`. Netty logs the path and the header name, and filters the query string and the header values.
- A tab or the byte 0xE9 in the same header passes Netty, reaches `RequestContextWebFilter`, and gets the `invalid_client_request_id` envelope.

## How to handle it

- The FND-04 contract (an envelope and a gateway `x-request-id` on every response WebFlux handles) cannot cover these responses: WebFlux never handles them. The systems entry lists them with Netty's other pre-WebFlux rejections ([systems/request-context-and-errors](../systems/request-context-and-errors.md), Failure behaviour).
- Any client can produce one WARN line per request this way. OBS-03 and OPS should treat this logger's WARN rate as client-driven, for example by rate-limiting or sampling it, rather than alerting on it as a server fault. Turning the logger off would also hide real decoding problems.
- A test that needs a control character to reach a filter must send one Netty accepts (tab), or call the filter through a mock exchange.
