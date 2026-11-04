---
id: gotchas/reactor-netty-answers-an-oversized-header-with-an-empty-431
type: gotcha
title: A request header over 8 KB gets Reactor Netty's empty 431, with no envelope and no x-request-id
status: active
tags: [reactor-netty, webflux, headers, errors, request-id, auth]
related: [gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux, gotchas/reactor-netty-refuses-an-unparsable-host-before-webflux, systems/request-context-and-errors, systems/auth-and-teams, runs/2026-11-04-smoke-teams-and-keys]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/AuthWebFilter.kt
---

## Summary

With Reactor Netty 1.3.8 (Spring Boot 4.1.2), a header over `server.max-http-request-header-size`, 8192 bytes by default, gets an empty 431 before WebFlux runs. No filter, no auth, no envelope, no `x-request-id`.

## Details

Seen in the [smoke run of GW-04](../runs/2026-11-04-smoke-teams-and-keys.md), finding 4. The request reaches neither `RequestContextWebFilter` nor `AuthWebFilter`, so an oversized `Authorization` gets no 401, and no route or provider is reached. Netty's WARN line filters header values and the query string: `POST /v1/chat/completions?<filtered> HTTP/1.1`. The path stays in it.

## How to handle it

- Do not write a test or a client that expects an OpenAI envelope for this: the FND-04 contract covers responses WebFlux handles. An SDK sees a 431 with an empty body.
- Anything counting responses by `x-request-id` misses these, as it misses the [control-character 400s](reactor-netty-rejects-control-chars-in-header-values-before-webflux.md).
- Any client can cause one WARN line per request this way; treat that logger's rate as client-driven.
