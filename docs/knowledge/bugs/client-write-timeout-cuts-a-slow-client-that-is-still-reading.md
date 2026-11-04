---
id: bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading
type: bug
title: client-write-timeout closes a client that never stalled, because a queued write's clock runs while it waits behind earlier frames
status: active
severity: low
tags: [streaming, sse, netty, back-pressure, timeouts]
related: [systems/provider-adapters, gotchas/netty-write-timeout-clock-starts-in-the-queue-and-logs-no-request-id, gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamProperties.kt
---

## Summary

GW-02 says a slow client slows the provider and nothing is dropped, and that `WriteTimeoutHandler` measures a stall. It measures from `write()` to the promise, queue wait included, so a client slower than the queued bytes divided by the timeout is closed while it is still reading.

## Symptom

Found in the GW-02 review. Probe: a real `HttpServer` with `WriteTimeoutHandler(2000 ms)`, an endless flow of 16 KB frames, and a client reading up to 16 KB every 50 ms, about 300 KB/s. The largest gap between reads was 171 ms, and the flow was cancelled at 2.6 s after 770 KB, with about 100 frames unread in the pipeline.

With the defaults (30 s, up to about 64 frames queued, figures from the spec's back-pressure table) the floor is about 2 frames per second once the kernel buffers are full: about 640 B/s for 300-byte frames, about 2 MB/s for frames of `max-event-size`. Scaled, not measured. Normal token streams are far above it, so the practical effect is a slow client closed and logged as `cancelled` on a large-frame stream, with the text cut.

## Root cause

`WriteTimeoutHandler.write` schedules its task when the write enters the pipeline, and `MonoSendMany` keeps up to 128 items unconfirmed, so a frame's promise waits for everything ahead of it. `ServerStreamConfiguration.kt` installed it as a stall detector, where `:24` now adds `ClientStallHandler`. The doc line "one write to a client's socket", then in `ServerStreamProperties.kt:6`, `application.yaml` and `systems/provider-adapters` Configuration, and ADR-GW-02-009's "a stall is what it measures" overstated it.

## Fix

Fixed in the GW-02 commit (Roadmap: GW-02), by the orchestrator's decision to change the mechanism. `ClientStallHandler.kt:17`, an `IdleStateHandler` with `observeOutput`, replaces `WriteTimeoutHandler` at `ServerStreamConfiguration.kt:24`; it closes only with bytes pending and no write progress for the timeout. Docs and ADR-GW-02-009 revised. What remains: progress is seen as the kernel takes bytes, so a reader below one kernel step per timeout is still cut.

## Prevention

`StalledClientStreamTest`: readers at 640 and 160 KB/s with 16 KB frames and a 1 s timeout get every frame (red before the fix: cut at about 1.2 s), 32 KB/s on Linux, a client that stops is cut and the socket closes, a slow provider with nothing pending is not a stall. `StreamStarvedUpstreamTest` pins what `idle-timeout` still does to such a client.

## Revision (2026-11-04)

"What remains" under Fix is not all that remains. The stall limit binds the connection only: above it a slow reader can still lose the stream to `idle-timeout`, at the defaults below about 11.8 to 19.7 KB/s on 16 KB frames, with an `upstream_timeout` frame that blames a healthy provider (smoke R1, [gotcha](../gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout.md)). So "nothing is dropped" no longer stands as written; the follow-up is GW-08. The limit in the Fix is measured now: 2 to 3 KB/s at the 30 s default.

## Revision (2026-11-04, second review)

`observeOutput`, named under Fix, is gone: it read queue growth as progress ([bug](client-stall-handler-misses-a-stall-while-the-queue-grows.md)). `ClientStallHandler` now compares the outbound buffer itself, with the same limits.
