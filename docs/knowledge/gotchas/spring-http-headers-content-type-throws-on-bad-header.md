---
id: gotchas/spring-http-headers-content-type-throws-on-bad-header
type: gotcha
title: HttpHeaders.getContentType() throws InvalidMediaTypeException on a bad value, on the server and inside WebClient's body read
status: active
tags: [spring, webflux, webclient, errors]
related: [systems/provider-adapters, bugs/adapter-bad-provider-content-type-gives-500]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/RequestBodies.kt
---

## Summary

Spring 7.0.10's `HttpHeaders.getContentType()` does not return null for a value it cannot parse: it throws `InvalidMediaTypeException`, an `IllegalArgumentException` whose message quotes the value. An empty `charset=` and an unknown charset count as unparsable.

## Details

- Server: `ServerRequest.headers().contentType()` throws. A functional route that does not catch it gives a 500 for a client's bad header.
- Client: `ClientResponse.toEntity(...)` and `bodyToMono(...)` read the type in `BodyExtractors`, so a provider's bad header fails the body read on any status, `exchangeToMono` included. Probed: `garbage`, `text/html; charset=`, `text/html; charset=no-such-charset`.
- WebTestClient refuses to send such a value, so a test needs a plain client or a raw socket.

## How to handle it

Server side: catch it where the type is read and answer 415 (`RequestBodies.kt:33-38`). Client side: remove the header before reading, `r.mutate().headers { it.remove(HttpHeaders.CONTENT_TYPE) }.build()`, when the type does not matter.
