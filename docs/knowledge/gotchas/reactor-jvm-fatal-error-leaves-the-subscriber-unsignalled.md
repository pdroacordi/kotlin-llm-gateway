---
id: gotchas/reactor-jvm-fatal-error-leaves-the-subscriber-unsignalled
type: gotcha
title: A JVM-fatal error inside a Reactor callable sends no signal, so an await on it never resumes
status: active
tags: [reactor, coroutines, errors, memory]
related: [bugs/embedding-scan-oom-leaves-the-call-hanging]
created: 2026-11-04
updated: 2026-11-04
source_files: []
---

## Summary

On Reactor 3.8.8 an `OutOfMemoryError` thrown in `Mono.fromCallable` under `subscribeOn` is rethrown on the worker by `throwIfFatal`, not passed to `onError`. The subscriber gets nothing.

## Details

Observed with `Mono.fromCallable { throw OutOfMemoryError("x") }.subscribeOn(Schedulers.parallel()).block(3s)`: a WARN `throwIfFatal detected a jvm fatal exception`, an ERROR `Scheduler worker ... failed with an uncaught exception`, then `IllegalStateException: Timeout on blocking read`. The stack passes through `FluxSubscribeOnCallable$CallableSubscribeOnSubscription.run`. An `await` on such a Mono would stay suspended.

## How to handle it

Put a `.timeout(...)` downstream of a callable that allocates in proportion to its input. The timer is a separate task, so it can still fire.
