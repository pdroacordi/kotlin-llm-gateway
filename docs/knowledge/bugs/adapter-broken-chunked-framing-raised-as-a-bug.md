---
id: bugs/adapter-broken-chunked-framing-raised-as-a-bug
type: bug
title: A provider's broken chunked framing leaves the adapter as a bare exception, so a 2xx gives 500, and after [DONE] an internal_error frame follows
status: active
severity: medium
tags: [adapters, openai, streaming, sse, reactor-netty, errors]
related: [systems/provider-adapters, gotchas/netty-bad-chunked-framing-raises-runtime-exceptions-through-webclient, patterns/cold-event-flow-commits-at-first-frame]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatStream.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamFrames.kt
---

## Summary

A 2xx whose chunked body has an invalid chunk-size line, or a chunk line over 4096 bytes, raises an exception `OpenAiTransportFailures` does not name, so it rethrows as a bug. The port's promise that an expected failure is a value breaks, and if it lands in the drain, the client gets an `internal_error` frame after `data: [DONE]`.

## Symptom

Found in the GW-02 review with a raw socket provider against the built adapter, `ChatCompletionService` and `ChatStreamFrames`:

| Provider sends | Expected (GW-02 section 4) | Observed |
|---|---|---|
| 200, then `ZZZ` as a chunk size | 502 `upstream_error` | `NumberFormatException` thrown from the flow: 500 `internal_error`, ERROR with a stack trace |
| One chunk, then `ZZZ` | `upstream_error` frame | `internal_error` frame, `Stream crashed after commit`, outcome `Crashed` |
| One chunk, then a 20 000-byte line | `upstream_error` frame | `TooLongHttpLineException`, same as the row above |
| `[DONE]`, then `ZZZ` in the drain | Nothing more: the drain is silent | `data: [DONE]`, then an `internal_error` frame; outcome `Completed` and an ERROR line |

The messages are fixed text, so no provider bytes reach the log. `complete` has the same hole: the same body raises `NumberFormatException` from `OpenAiChatAdapter.complete`.

## Root cause

Netty's `HttpObjectDecoder` reports bad framing through `decoderResult`, and Reactor Netty passes the cause to the body flux as is. `NumberFormatException` and `TooLongHttpLineException` are runtime exceptions, not `IOException`. `OpenAiTransportFailures.kt:55-61` names only `IOException`, `DataBufferLimitException`, `DecodingException` and `WebClientResponseException`, and rethrows the rest; `OpenAiChatStream.kt:53-56` calls it before `translator.fail`, so the drain's silence is bypassed. `ChatStreamFrames.kt:41-44` emitted a crash frame whenever `started` was true, with no check that a terminal frame had gone out. `OpenAiChatAdapter.kt:127-135` catches the same named types.

A TLS failure mid-body should take the same route, since `ByteToMessageDecoder` wraps it in `DecoderException`. That is read from Netty, not run: the mock is plain HTTP.

## Fix

Fixed in the GW-02 commit (Roadmap: GW-02). `bodyFailuresAsTransport` (`OpenAiTransportFailures.kt:32`) maps any error from a response body to `UpstreamBodyFailure`, a stackless `IOException` that keeps only the class name for the log. It runs right on the body, before `copyAndRelease`, at `OpenAiChatStream.kt:93`, and on the non-stream and refused reads through `withoutContentType` (`OpenAiChatAdapter.kt:46`). A bug in `feed` keeps rethrowing, as it sits below that operator. `ChatStreamFrames.kt:43` logs and emits nothing once a terminal frame went out.

## Prevention

`RawProviderStreamIntegrationTest`: `ZZZ` and a 20 000-byte line first, mid-stream and in the drain. `RawProviderSocketIntegrationTest`: the same on `complete`, on a 200 and a 500. `ChatStreamFramesTest`: a bug after `[DONE]` or after an error frame adds no frame. All red before the fix, by the raised exception or a frame count.
