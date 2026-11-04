---
id: bugs/stalled-test-read-loop-spins-on-eof
type: bug
title: StalledClientStreamTest.readBody appends for ever on EOF, so a wrongly cut stream kills the test JVM instead of failing a test
status: active
severity: low
tags: [testing, streaming]
related: [bugs/client-stall-handler-misses-a-stall-while-the-queue-grows]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/StalledClientStreamTest.kt
---

## Summary

`readBody` loops on `body.append(input.read().toChar())` until it sees the last chunk. `read()` returns -1 at EOF, immediately and for ever, so a connection closed too early grows a `StringBuilder` until the worker dies.

## Symptom

Second-pass review mutation: with `pending > 0` removed from `ClientStallHandler.kt:40`, `a slow provider with nothing pending for the client is not a stall` should fail. Instead the worker died with `OutOfMemoryError: Java heap space` at `StalledClientStreamTest.kt:216`, Gradle reported `Gradle Test Executor 7 FAILED`, and the class report held 4 of its 6 tests, with 0 failures. The build is red, but the report names no test, and this is the test guarding the handler's main false-positive case.

## Root cause

`StalledClientStreamTest.kt:216` does not check for -1. `readPaced` in the same file does (`if (read < 0) break`).

## Fix

`StalledClientStreamTest.kt:261` fails on EOF, as `readUntil` in `StreamDisconnectIntegrationTest.kt` does: `check(byte >= 0) { "EOF before the last chunk: $body" }`. Commit: the second-review fix commit.

Proof: with the pending check removed from `StallClock`, `a slow provider with nothing pending for the client is not a stall` now fails by name with `IllegalStateException: EOF before the last chunk`, and the worker lives.

## Prevention

Any byte-at-a-time read loop in a test checks for -1.
