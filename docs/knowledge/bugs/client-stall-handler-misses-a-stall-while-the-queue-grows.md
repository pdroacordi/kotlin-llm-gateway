---
id: bugs/client-stall-handler-misses-a-stall-while-the-queue-grows
type: bug
title: A stopped client on a slow stream is cut 27 timeouts late, because frames joining the queue read as write progress
status: active
severity: low
tags: [streaming, back-pressure, netty, timeouts]
related: [systems/provider-adapters, gotchas/netty-idle-observe-output-counts-queue-growth-as-progress, bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
---

## Summary

`ClientStallHandler` keeps silent on any tick where the outbound queue changed, and a new frame changes it. While a provider sends at least one frame per stall window, a client that reads nothing is not cut until demand runs out, against "1 to 1.5 timeouts after the last progress".

## Symptom

Second-pass review probe, real socket, `client-write-timeout` 1 s, `SO_SNDBUF` 16 KB, a client that never reads, a fake provider sending one 16 KB frame every 200 ms: cut after 27554 ms, with 134 frames emitted and 2120706 bytes pending. The same client on an endless stream at full speed is cut in about 1.3 s.

Scaled by reasoning, not measured: at the default 30 s, any stream whose frames are under 30 s apart keeps a dead client's upstream connection for up to about 134 frames, bounded by `total-timeout`. A normal token stream reaches that in seconds, so only a slow stream is affected. The tokens at stake are few; the cost is the held connection and pool slot.

## Root cause

`ClientStallHandler.kt:17` passes `observeOutput = true`. `IdleStateHandler.hasOutputChanged` compares `totalPendingWriteBytes`, which also rises when a frame is queued ([gotcha](../gotchas/netty-idle-observe-output-counts-queue-growth-as-progress.md)). The silent tick makes the next event a gap, and `ClientStallHandler.kt:31-32` restarts the count. Queueing stops only when `MonoSendMany` runs out of demand, at 128 unconfirmed items.

By its code, not measured: the replaced `WriteTimeoutHandler` would have cut this client at its timeout, so the fix round traded this corner for the slow-reader one it solved.

## Fix

`ClientStallHandler.kt:20` builds with `observeOutput` off, so every tick reaches `channelIdle`. `StallClock` (`:56`, `:74`) restarts the count on a completed write (`event.isFirst`), a new `buf.current()` or a change in `buf.currentProgress()`, and `totalPendingWriteBytes` decides only whether anything is pending. The gap check went with the silent ticks. `isFirst` stays beside `current()` because Netty recycles buffers, so the first message can look the same after a whole write. Commit: the second-review fix commit.

Same probe, through `StalledClientStreamTest`: red before the fix (not cut within 10 s; 27.5 s in the review), cut in 1.6 s after it, with 100986 bytes pending instead of 2120706. The healthy readers at 640 and 160 KB/s still get every frame.

## Prevention

`StalledClientStreamTest.kt:128`, `a stopped client on a slow stream is cut while frames keep arriving`: one 16 KB frame every 200 ms, cut within 10 s, the wait of `assertCancelled`. When the kernel took its last byte cannot be seen from outside, so the bound is that wait, not 1.5 timeouts; `StallClockTest` pins the count itself, with a mutant of each branch red.
