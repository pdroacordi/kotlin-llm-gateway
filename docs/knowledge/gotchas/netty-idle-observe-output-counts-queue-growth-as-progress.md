---
id: gotchas/netty-idle-observe-output-counts-queue-growth-as-progress
type: gotcha
title: IdleStateHandler with observeOutput treats bytes joining the queue as output progress
status: active
tags: [netty, timeouts, back-pressure, streaming]
related: [bugs/client-stall-handler-misses-a-stall-while-the-queue-grows, gotchas/netty-write-timeout-clock-starts-in-the-queue-and-logs-no-request-id]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
---

## Summary

On Netty 4.2.19, `IdleStateHandler.hasOutputChanged` reports a change when `totalPendingWriteBytes` differs from the last tick, in either direction. A new write into a socket that takes nothing raises that total, so a tick that saw only growth stays silent, as if the peer had read.

## Details

`IdleStateHandler.java`, `hasOutputChanged`: the check is `messageHashCode != lastMessageHashCode || pendingWriteBytes != lastPendingWriteBytes`, then `return true` on a non-first call. Its comment calls this a change "on message level", and the method cannot tell the queue draining from the queue filling.

So `observeOutput` answers "did the outbound buffer change", not "did the peer take bytes". Only `currentProgress()` and the completion of a write mean the peer took bytes.

## How to handle it

- To detect a peer that takes nothing, do not rely on `observeOutput` alone while a producer can still write. Compare `buf.current()` and `buf.currentProgress()` yourself, and reset on a completed write.
- A producer that writes at least once per stall window hides the stall until something else stops it, here the demand limit of `MonoSendMany` ([bug](../bugs/client-stall-handler-misses-a-stall-while-the-queue-grows.md)).

## Revision (2026-11-04)

`ClientStallHandler` does this since the second-review fix commit: `observeOutput` off, `StallClock` for the comparison.
