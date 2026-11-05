---
id: bugs/embedding-scan-oom-leaves-the-call-hanging
type: bug
title: An OutOfMemoryError in the embedding scan leaves the call hanging with no deadline, holding its body
status: active
severity: medium
tags: [embeddings, reactor, memory, timeouts, gw-05]
related: [gotchas/reactor-jvm-fatal-error-leaves-the-subscriber-unsignalled, bugs/embedding-max-connections-does-not-cap-reply-memory]
created: 2026-11-04
updated: 2026-11-05
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
---

## Summary

The scan is the one stage after `exchange` with no deadline. If it raises a JVM-fatal error the coroutine is never resumed: the client gets no answer, and the suspended frame keeps the body alive until the client disconnects.

## Symptom

Found in review. A standalone probe on Reactor 3.8.8, `Mono.fromCallable { throw OutOfMemoryError() }.subscribeOn(Schedulers.parallel())`, logged `throwIfFatal detected a jvm fatal exception` and sent no signal; `block(3s)` timed out. The adapter itself was not run this way. GW-05 section 5 accepts heap pressure on this route, so `copyOfRange` failing is a likely way for that pressure to surface.

## Root cause

`OpenAiEmbeddingAdapter.kt:107-111`. `requestTimeout` is applied inside `UpstreamClient.post`, so it has ended before the scan. A scan queued behind others on `parallel` is unbounded for the same reason.

## Fix

Fixed in GW-05 (Roadmap: GW-05). Every stage on `CpuLane` has `.timeout(deadline)` after `subscribeOn`, and the adapter maps `TimeoutException` to `UpstreamTimeout`. `OpenAiEmbeddingAdapterFailureTest` "a JVM-fatal error in the scan ends the call with UpstreamTimeout at the deadline".

## Prevention

An adapter test with a reader that throws an `Error`: the call must end within the deadline.
