---
id: gotchas/reactor-flux-timeout-takes-publishers-and-ignores-demand
type: gotcha
title: Flux.timeout with a per-item factory has no Duration form, and its timer runs while downstream has no demand
status: active
tags: [reactor, timeouts, back-pressure, streaming]
related: [systems/provider-adapters, bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading, gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatStream.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/StreamDeadlines.kt
---

## Summary

In Reactor 3.8.8, `Flux.timeout(Publisher, Function)` is the only first-and-per-item form: both arguments are publishers, with no `Duration` overload. The next timer starts at each `onNext` and ignores demand, so a slow consumer looks like a silent producer.

## Details

`javap` lists `timeout(Duration)`, `(Duration, Publisher)`, `(Duration, Scheduler)`, `(Duration, Publisher, Scheduler)`, `(Publisher)`, `(Publisher, Function)` and `(Publisher, Function, Publisher)`.

Probe: the real adapter with `idle-timeout` 1500 ms against a raw provider writing an event every 2 ms, and a collector that pauses for 4 s at event 20. The provider was healthy and still writing at 1.9 s when its connection was aborted, and when the collector resumed it got the few batches already buffered, then `Failed(UpstreamTimeout)`. The adapter's WARN names the provider for what the consumer did.

## How to handle it

Write `.timeout(Mono.delay(first), Function { Mono.delay(next) })`, as `OpenAiChatStream.kt:103-106` does; each item schedules one delay on `parallel`. Keep a stall in the consumer on a shorter clock than the producer's idle deadline (`client-write-timeout` below `idle-timeout`, checked at startup). A consumer that stops and starts can still fire it, which ADR-GW-02-009 accepts, so RTE-02's circuit breaker should not count one `upstream_timeout` after commit as proof of a sick provider.

## Revision (2026-11-04)

Not only a consumer that stops and starts: with `ClientStallHandler` in place of the write timeout, a client reading steadily fires it too, at the defaults below about 11.8 to 19.7 KB/s on 16 KB frames ([gotcha](client-stall-handler-moves-slow-readers-onto-idle-timeout.md)).
