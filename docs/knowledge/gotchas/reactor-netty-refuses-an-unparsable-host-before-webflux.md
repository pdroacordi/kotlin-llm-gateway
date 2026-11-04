---
id: gotchas/reactor-netty-refuses-an-unparsable-host-before-webflux
type: gotcha
title: A Host value Reactor Netty cannot parse gets an empty 400 before any WebFilter, so a mock exchange tests a path a socket never reaches
status: active
tags: [reactor-netty, webflux, testing, security, http]
related: [bugs/loopback-host-filter-passes-malformed-host-values, gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux, systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files: []
---

## Summary

On Reactor Netty 1.3.8, `HttpTrafficHandler` builds `ConnectionInfo` from `Host` before it creates the request's operations, and a value it cannot parse raises a `RuntimeException` that becomes a bare 400. WebFlux never runs, so there is no envelope and no `x-request-id`, and `LoopbackHostWebFilter` is not consulted.

## Details

Raw socket against `gateway-app`, one `POST /v1/chat/completions` per value, 2026-11-04:

| `Host` | Status | `x-request-id` | Decided by |
|---|---|---|---|
| `127.0.0.1`, `localhost:8080`, `[::1]:8080`, `[::1]`, `127.1` | 200 | yes | filter |
| `evil.example`, `127.0.0.1.`, `localhost.`, `0.0.0.0`, `127.0.0.1,evil.example` | 403 | yes | filter |
| empty, `:8080`, `127.0.0.1:123456`, `[127.0.0.1]:8080`, `[localhost]` | 403 | yes | filter |
| two `Host` lines, either order, one of them `evil.example` | 403 | yes | filter |
| `[::1].evil.example`, `[::1]evil.example`, `127.0.0.1:evil`, `127.0.0.1:80@evil.example`, `localhost:` | 400 | no | Reactor Netty |

`MockServerHttpRequest` has no such check, so `LoopbackHostWebFilterTest` sees a 403 for the last row.

## How to handle it

- A test of what a client receives for a header value goes through a socket; a mock exchange proves the filter, not the response.
- None of the last row is exploitable: every value is refused, by one layer or the other.
- Anything counting responses by `x-request-id` will miss these 400s, as it misses the control-character ones ([gotcha](reactor-netty-rejects-control-chars-in-header-values-before-webflux.md)).

## Revision (2026-11-04)

GW-04 deleted `LoopbackHostWebFilter` and its tests (ADR-GW-04-018), so the "filter" rows of the table describe a check that no longer exists: those values now reach the chain, and a team key is the control. The last row is unchanged, because it is Reactor Netty's own behaviour.
