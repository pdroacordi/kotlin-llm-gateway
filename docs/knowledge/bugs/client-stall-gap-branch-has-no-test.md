---
id: bugs/client-stall-gap-branch-has-no-test
type: bug
title: No test proves ClientStallHandler's gap check, and without it a live reader on a large frame is cut
status: active
severity: low
tags: [testing, streaming, netty, back-pressure]
related: [systems/provider-adapters, bugs/client-stall-handler-misses-a-stall-while-the-queue-grows, bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/StalledClientStreamTest.kt
---

## Summary

The check `now - lastEvent <= tickNanos + tickNanos / 2` is what keeps a client making intermittent progress from being counted as stalled. Removing it leaves every test green, on Windows and on Linux, so the code that stops the false cut this handler exists to prevent has no test.

## Symptom

Second-pass review, mutations of `ClientStallHandler.kt:31`, on real sockets:

| Mutation | `StalledClientStreamTest` |
|---|---|
| Drop the gap check, keep `!event.isFirst` | Green on Windows (1 skipped); green on Linux, 2 CPUs, `eclipse-temurin:25-jdk` (6 of 6, 0 skipped) |
| Drop `!event.isFirst`, keep the gap check | Red |
| Never restart the count | Red |
| Drop `pending > 0` | Red ([by a JVM death](stalled-test-read-loop-spins-on-eof.md)) |

The mutant is a real defect, not an equivalent one. On Linux, at a 1 s timeout with a 16 KB `SO_SNDBUF`, one 1 MB frame read in 48 KB bursts 500 ms apart:

| Handler | Bytes read | Result |
|---|---|---|
| As committed | 1048985 | Complete in 12.9 s, not cut |
| Gap check removed | 180456 | Cut at 4.0 s, EOF |

## Root cause

A silent tick returns before `channelIdle`, and `isFirst` stays false because no write completed. Only the gap in event times shows progress happened between two events. Every test's frames are small enough that a write completes within a tick, which resets the count through `isFirst`, so the gap path never decides anything. The Linux-only `a much slower reader is not cut either` also completes a 16 KB frame often enough.

## Fix

The gap check is gone: `observeOutput` off leaves no silent tick, so `StallClock` compares `currentProgress()` directly (`ClientStallHandler.kt:74`, [bug](client-stall-handler-misses-a-stall-while-the-queue-grows.md)). That comparison now does what the gap check did, and has the test: `StalledClientStreamTest.kt:186`, `a reader of one large frame in bursts is not cut, though no write completes`, `@EnabledOnOs(OS.LINUX)` for the same reason as its neighbour, since Windows takes hundreds of KB per step. One 1 MB frame in 48 KB bursts 500 ms apart for 6 s, then at full speed. Commit: the second-review fix commit.

Proof in `eclipse-temurin:25-jdk`, 2 CPUs, 1 s timeout, 16 KB `SO_SNDBUF`: 8 of 8 green in 6.1 s; with `headProgress != lastProgress` removed, red in 1.5 s. On Windows the same mutant is caught by `StallClockTest` only.

## Prevention

Each branch of a timing state machine gets a test that fails without it. Mutate the branch once to check, as this review did: it took one line and one run. `StallClock` holds the branches with no socket or timer, so `StallClockTest` goes red for each of the four conditions alone, on any OS. A real socket needs two removed to see a whole write: with only `currentProgress()` left, the 160 KB/s reader is cut.
