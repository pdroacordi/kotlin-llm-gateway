---
id: patterns/raw-sse-frames-flushed-per-event
type: pattern
title: Provider-exact SSE frames built as strings in pure code and written with one flush per event
status: active
tags: [sse, streaming, webflux, openai-compat, anthropic, coroutines]
related: [systems/mock-provider, patterns/one-error-funnel, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/sse/SseFrames.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/SseBodyInserter.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/LatencyIntegrationTest.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseFrames.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
---

## Summary

A stream that must match a provider's bytes (OpenAI `data: <json>`, Anthropic `event: <type>\ndata: <json>`) and reach the client event by event is built as complete frame strings in pure code. A `BodyInserter` writes them through `ServerHttpResponse.writeAndFlushWith`, one inner publisher per event, so each event is flushed when it is produced.

## Details

- **Frames in pure code** (`SseFrames.kt`): `openAiData(json)` gives `data: <json>\n\n`, `OPENAI_DONE` is `data: [DONE]\n\n`, `anthropicEvent(type, json)` gives `event: <type>\ndata: <json>\n\n`. A value containing `\n` or `\r` is rejected: compact JSON never has one, and one would split the event. The renderers build the JSON as explicit trees, so member presence and order are exact (`"usage":null` versus no `usage`).
- **Writer** (`SseBodyInserter.kt:17`): `BodyInserter<Flow<String>, ReactiveHttpOutputMessage>` whose `insert` returns `outputMessage.writeAndFlushWith(frames.asFlux().map { Mono.fromSupplier { buffers.wrap(it.encodeToByteArray()) } })`. The handler returns `ServerResponse.ok().contentType(text/event-stream;charset=UTF-8).header(Cache-Control, no-cache).body(inserter)` from a `coRouter` handler.
- **Timing** lives in the `flow {}` that produces the frames: a Reactor timer between events (`awaitDelay`, `Mono.delay(...).awaitSingle()`), never a blocking sleep and not `kotlinx.coroutines.delay`, which under Spring's `Unconfined` start resumes on kotlinx's single `DefaultExecutor` thread, out of BlockHound's sight ([gotcha](../gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)). `Serving.kt`, `stream` and `ending`.
- **Outcome bookkeeping** in the same `flow {}`: a `try`/`finally` that records a cancellation by `!currentCoroutineContext().isActive`, so the `CancellationException` is never caught.
- **Proof of flushing**: `LatencyIntegrationTest` reads raw `DataBuffer`s with a `WebClient` over real Netty and times each completed event. At `perTokenMs: 200` the pieces arrived 200 to 202 ms apart (Reactor Netty 1.3.8, Spring Framework 7.0.10). The test asserts lower bounds and the spread of the pieces (at least 600 ms), never an upper bound, so a loaded runner cannot fail it. A buffered stream would deliver every event at the end. The golden files compare whole bodies byte for byte.

## Why

- Spring's `ServerSentEvent` writer emits `data:` and `event:` with no space after the colon. The WHATWG SSE parser strips one leading space, so both parse the same, but golden byte comparisons and naive line-matching clients do not (ADR-FND-03-007).
- Returning `Flow<String>` with `text/event-stream` makes Spring wrap each element as an SSE `data:` event: a JSON frame that already carries `data: ` is framed twice.
- `writeAndFlushWith` is documented to flush after each inner `Publisher<DataBuffer>` (Spring Framework `ReactiveHttpOutputMessage` javadoc); Reactor Netty sends each group and flushes it.
- Sources: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ReactiveHttpOutputMessage.html, https://html.spec.whatwg.org/multipage/server-sent-events.html, https://github.com/spring-projects/spring-framework/blob/7.0.x/spring-web/src/main/java/org/springframework/http/codec/ServerSentEventHttpMessageWriter.java

## Gotchas / pitfalls

- One flush per event costs a syscall and a TCP segment per event. That is right for token streams that must be seen as they come. Batch events into one inner publisher only where latency does not matter.
- Errors after the first frame cannot change the status: the response is committed. Turn an expected failure into an in-band event (OpenAI `data: {"error":{...}}`, Anthropic `event: error`) before it leaves the flow. An exception that does escape makes Reactor Netty close the connection without the terminating chunk (the client sees a premature close), which is right only for a deliberate abort.
- Never swallow `CancellationException` in the frame-producing flow. A client disconnect arrives as cancellation, and catching it keeps a dead stream "running". Record it in `finally`, and let it propagate.
- A frame string with a line break in the JSON silently splits into two events; reject it at build time, as `SseFrames` does.
- `kotlinx-coroutines-reactive` initialises `ReactiveFlowKt` (a `ServiceLoader` jar read) on first use. Initialise it at startup when BlockHound guards the event loop (see the systems entry, Gotchas).

## Revision (2026-11-03)

GW-02 applies this pattern in `gateway-app`, with four differences from the mock (ADR-GW-02-004, ADR-GW-02-007):

- Frames are `Flow<ByteArray>`, not `Flow<String>`: the JSON is serialised once and never decoded again.
- `frames.asFlux(context)` takes the handler's coroutine context without its `Job`, because the default `Unconfined` context drops `RequestContext` and MDC, and a `Job` would tie the flow to a handler that has already returned.
- The builder carries no headers: they are set at the first frame ([cold-event-flow-commits-at-first-frame](cold-event-flow-commits-at-first-frame.md)).
- An expected failure after commit ends the flow normally, after the in-band frame. Only the mock's deliberate aborts escape as exceptions, and those are what produce Reactor Netty's ERROR line.
