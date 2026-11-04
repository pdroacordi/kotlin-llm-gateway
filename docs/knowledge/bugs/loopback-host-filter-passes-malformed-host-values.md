---
id: bugs/loopback-host-filter-passes-malformed-host-values
type: bug
title: LoopbackHostWebFilter passes a Host that only starts like a loopback name, such as [::1].evil.example or 127.0.0.1:evil
status: active
severity: low
tags: [security, webflux, dns-rebinding]
related: [systems/provider-adapters, bugs/grafana-anonymous-admin-reachable-through-dns-rebinding]
created: 2026-11-03
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/security/LoopbackHostWebFilter.kt
---

## Summary

The parser keeps what is before the first `:`, or up to the first `]`, and ignores the rest. `[::1]evil.example`, `[::1].evil.example`, `127.0.0.1:evil`, `127.0.0.1:80@evil.example` and `localhost:evil` all pass. A browser cannot send these, because none is a URL host with a port, so DNS rebinding does not reach this; the check still accepts values that are not loopback hosts.

## Symptom

Found in the GW-01 review through the filter's real `isLoopback`. Every realistic rebinding value is refused: `evil.example:8080`, `127.0.0.1.nip.io`, `foo.localhost`, `localhost.`, `127.0.0.1,evil.example`, an unbracketed `::1`, `[::]:8080`, `0:8080`, an empty value.

## Root cause

`isLoopback` (`LoopbackHostWebFilter.kt:36`) never looked at what follows the name.

## Fix

`LoopbackHostWebFilter.kt:37` matches the whole value against `HOST` (`:45`): `<name>` or `<name>:<digits>`, with a bracketed name holding a colon, so `[127.0.0.1]` and `[localhost]` are refused too. Group 1 goes to `ExposureGuard.isLoopback`.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`LoopbackHostWebFilterTest` "any other Host is a 403": the five values of the Summary plus `localhost:`, `127.0.0.1:123456`, `[127.0.0.1]:8080` and `[localhost]`; seven were red first.

## Revision (2026-11-04)

The fix is correct, but its tests prove less than they read. `LoopbackHostWebFilterTest` is a mock exchange, and `any other Host is a 403` lists five of this entry's values that a real server never brings to the filter. Over a raw socket `[::1].evil.example`, `[::1]evil.example`, `127.0.0.1:evil`, `127.0.0.1:80@evil.example` and `localhost:` get Reactor Netty's empty 400 with no `x-request-id` ([gotcha](../gotchas/reactor-netty-refuses-an-unparsable-host-before-webflux.md)). Nothing passes: both layers refuse every malformed value tried, `127.0.0.1:123456` and `[localhost]` reach the filter and get 403, and two `Host` lines get 403 in either order. `systems/provider-adapters` names only `Host: [::1` as refused before the filter.

Closed in the second-review fix commit: `LoopbackHostSocketTest.kt:79` holds those values over a real socket, with `[::1` (empty 400, no `x-request-id`, no provider call), `:90` the two that reach the filter, `:96` two `Host` lines in either order (403), and `:60` a loopback `Host` reaching the app, so the refusals are not vacuous. `systems/provider-adapters` names them all. `LoopbackHostWebFilterTest` keeps the filter's own rule, with a comment saying which layer a socket meets first.
