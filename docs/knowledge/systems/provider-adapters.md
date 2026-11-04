---
id: systems/provider-adapters
type: system
title: Provider adapters: the OpenAI adapter behind ChatModelPort, with every upstream failure a sealed value
status: active
tags: [adapters, openai, openai-compat, webclient, errors, json, blockhound, sse, streaming]
related: [patterns/typed-core-with-json-extras, patterns/upstream-bytes-then-pure-classification, patterns/one-error-funnel, patterns/cold-event-flow-commits-at-first-frame, patterns/copy-and-release-pooled-buffers-before-a-flow, patterns/raw-sse-frames-flushed-per-event, systems/request-context-and-errors, systems/mock-provider, gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay, gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout, gotchas/pooled-connection-closed-without-connection-close-costs-a-502, bugs/gateway-body-limit-on-functional-route-gives-500, bugs/grafana-anonymous-admin-reachable-through-dns-rebinding, bugs/adapter-broken-chunked-framing-raised-as-a-bug, bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running, bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading, runs/2026-11-04-smoke-sse-streaming]
created: 2026-11-03
updated: 2026-11-04
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/json/JsonValue.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatModelPort.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatCompletionService.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/UsageEstimator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatChunk.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatStream.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/StreamTally.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatStream.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiRequestWriter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChunkReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiUsageReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiStreamTranslator.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/StreamDeadlines.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/sse/SseEventParser.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiResponseReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiFailureClassifier.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/RetryAfter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatChunkWriter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamFrames.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamLog.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseFrames.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/StreamErrorFrames.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamProperties.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/OpenAiProperties.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/security/LoopbackHostWebFilter.kt
---

## Summary

`gateway-providers` holds the OpenAI adapter behind `ChatModelPort`. `POST /v1/chat/completions` reaches `ChatHandler`, `ChatCompletionService` resolves the deployment and calls the port, and the adapter makes one upstream POST with the configured key. It hands status, headers and bytes to pure functions, which give a `ProviderResult`. Any upstream failure is a sealed `ProviderFailure`, which the mapper renders as one row of GW-01 section 4. A slow or absent provider costs at most `request-timeout`, and nothing is retried. With `stream: true` the same call is a cold flow of sealed events, translated chunk by chunk and written one flushed frame per event, committing the status at the first frame. Contract: [GW-01 spec](../../specs/GW-01-chat-completions.md), section 4, and [GW-02 spec](../../specs/GW-02-sse-streaming.md), section 4.

## Contract (never break)

1. Provider types never cross the port. A stream is translated chunk by chunk, never buffered whole. Usage comes from the provider; an estimate is used only when the provider omits it, and is flagged `estimated`. ARCH-1, ARCH-12, `internal` visibility, `ChatCompletionServiceTest`; for a stream `OpenAiChatStreamIntegrationTest`, `SlowClientStreamTest`, `StreamEndToEndTest` (timing).
2. Every outbound call has a connect timeout and a whole-call deadline; a stream has first-byte, idle and total deadlines. `OpenAiChatAdapterIntegrationTest` (`hang` with a 500 ms deadline), `OpenAiChatStreamIntegrationTest`.
3. An adapter never retries; retries and fallback are RTE-02's, before the first byte. `OpenAiChatAdapterIntegrationTest`: one failed call, one journal entry.
4. A provider's `message` is never relayed or logged; only `code` and `param` that pass the regex are. `OpenAiFailureClassifierTest`, `NoLeakIntegrationTest`.
5. The provider key is never logged or returned; the client's credential is never forwarded. `ApiKeyTest`, `StartupFailureTest`, `NoLeakIntegrationTest`, the journal fingerprint in `ChatCompletionsEndToEndTest`.
6. Provider response headers reach the client only through `retry-after` and `retry-after-ms`. `UpstreamErrorMappingTest`.
7. Members the gateway does not model pass through verbatim in both directions. `OpenAiShapeContractTest`, `ChatCompletionsEndToEndTest`.
8. A cancelled caller cancels the upstream call; `CancellationException` is never caught. `ClientDisconnectIntegrationTest`, `OpenAiChatAdapterIntegrationTest`.
9. A failure before the first frame is a status; after it, an SSE error frame with the same envelope, no `[DONE]`, and a clean end. `StreamUpstreamErrorMappingTest`, `StreamFailureIntegrationTest`.
10. The gateway always requests usage upstream; a client sees it only if it asked. `StreamShapeContractTest`.
11. No pooled buffer crosses into a `Flow`. `StreamBufferLeakIntegrationTest`.
12. A stream has exactly one end-of-stream report, whatever ended it. `StreamTallyTest`, `StreamDisconnectIntegrationTest`.

## How it works

- **Modules.** `gateway-core` holds the domain, port, service, estimator and reply table, and knows no JSON library or SDK. `gateway-providers` owns `spring-webflux` (WebClient only, with `spring-context` because its connector needs it), `reactor-netty-http` and Jackson's tree model. `gateway-app` holds the route, reader, writer, properties and wiring. Only `.gateway.app.config` names `.gateway.providers.openai` (ARCH-12); `.gateway.providers.json` is exempt.
- **Domain** ([patterns/typed-core-with-json-extras](../patterns/typed-core-with-json-extras.md)). Typed fields for what the gateway reads, `extras: JsonValue.Object` at each level for the rest, in order. Numbers stay JSON text. Text-carrying types override `toString`.
- **JSON.** Every body is read through `JsonMapper.exactTreeReader()` and `readTreeOrNull` (`JsonTrees.kt`): floats stay `BigDecimal`, trailing zeros stay, one value only, and an unreadable body is `null`. The conversion to and from `JsonValue` is by hand. An exponent keeps its value, not its spelling: `1e-5` goes out as `0.00001`.
- **The call** (`OpenAiChatAdapter.complete`). `OpenAiRequestWriter` builds the body with `model` from the deployment, then extras. WebClient posts it with `Authorization: Bearer <configured key>` and `X-Client-Request-Id: <gateway request ID>`, `exchangeToMono { toEntity(ByteArray) }` reads every status on one path, and one `Mono.timeout(request-timeout)` bounds the whole call. No other client header is forwarded.
- **The result** ([patterns/upstream-bytes-then-pure-classification](../patterns/upstream-bytes-then-pure-classification.md)). A readable 2xx goes through `OpenAiResponseReader`; malformed `usage` counts as omitted, so a paid answer is not thrown away. An unreadable 2xx is `UpstreamError`. Other statuses go to `OpenAiFailureClassifier`, which reads only `code`, `type` and `param` and checks them against the section 4 regexes. A usage whose total would not fit a `Long` counts as omitted. The provider's `Content-Type` is dropped before the body is read, so a value that cannot be parsed changes nothing.
- **Transport failures** (`OpenAiTransportFailures`, in this order):
  1. A top-level `TimeoutException`, our `Mono.timeout`: `UpstreamTimeout`.
  2. `WebClientRequestException` whose cause chain says nothing was sent (refused or unreachable, unknown host, TLS handshake, pool acquire timeout or full queue): `ProviderUnavailable(null)`.
  3. Any other `WebClientRequestException`: `UpstreamError`. The provider may have the call, and may bill it, so RTE-02 must not repeat it.
  4. `IOException` (with `PrematureCloseException`), `DataBufferLimitException`, `DecodingException`, `WebClientResponseException`: `UpstreamError`.
  5. Anything else rethrows: a bug, so a 500. The adapter catches named types only.

  Any error from a response's body becomes `UpstreamBodyFailure`, a stackless `IOException` keeping only the class name for the log, on the stream and non-stream reads: nothing of ours runs above it, and Netty raises bad chunked framing as bare runtime exceptions ([bug](../bugs/adapter-broken-chunked-framing-raised-as-a-bug.md)). So bad framing is 502 before commit, an `upstream_error` frame after it, and silent in the drain.

  Mid-body through `toDataBuffers()`, measured: a close before the terminating chunk (`disconnect`) raises `PrematureCloseException`, a reset a bare `SocketException`, all three deadlines a top-level `TimeoutException`; a closed port on a stream is `WebClientRequestException`.

  Measured through WebClient 7.0.10 against the mock: a closed port and a full pool raise `WebClientRequestException`, `hang` past the deadline a top-level `TimeoutException`, `disconnect` after the headers a bare `PrematureCloseException`, a response over the limit a bare `DataBufferLimitException`. Against a raw socket (`RawProviderSocketIntegrationTest`): a reset after the write, a reply that is not HTTP and headers over 8 KB raise `WebClientRequestException` with no "nothing sent" cause, so all are 502; a provider `Content-Type` that cannot be parsed raised `InvalidMediaTypeException` from the body read until the adapter dropped the header.
- **Streams.** `ChatModelPort.stream` is a cold `Flow<ProviderStreamEvent>`: chunks, then one terminal event, with an expected failure a value at any position ([patterns/cold-event-flow-commits-at-first-frame](../patterns/cold-event-flow-commits-at-first-frame.md)). `OpenAiChatStream` makes the same POST with `Accept: text/event-stream`, `stream: true` and `include_usage: true`, whatever the client asked. Each `DataBuffer` is copied and released in the operator that receives it ([patterns/copy-and-release-pooled-buffers-before-a-flow](../patterns/copy-and-release-pooled-buffers-before-a-flow.md)), then `SseEventParser` (bytes, WHATWG, CR and BOM included) and `OpenAiChunkReader` feed `OpenAiStreamTranslator`, one per stream. A typed field holds only a value of its type, so presence and `null` survive in `extras`. One `Flux.timeout(Mono.delay(first), { Mono.delay(next) })` gives first-byte, idle and total from the pure `StreamDeadlines`, and a 1 s drain after `[DONE]`, so a connection returns to the pool (`Flux.timeout` takes publishers, not durations). `StreamTally` in core marks `Started`, forwards or drops the usage chunk, synthesises one from character counts when the provider omitted it and the client asked (`llmgw`, `ChatChunkWriter.EXTENSION_MEMBER`), and reports `StreamSummary` once. `ChatStreamFrames` sets the commit headers at `Started` on the still-uncommitted response; a `Failed` before it is thrown, so `ChannelSendOperator` bypasses the write and the mapper writes a status; after it, a frame and a normal end. `SseBodyInserter` writes one flushed frame per element through `asFlux(context)` with the handler's context minus its `Job`, so `RequestContext` and MDC hold, also in the `finally` of a cancelled flow.
- **Back-pressure.** Demand runs end to end: `MonoSendMany` 128 buffers unconfirmed, `sendGroups` 32 frames, `asFlux` suspends at zero demand, `asFlow().buffer(8)`, then `FluxReceive` turns `autoRead` off. Per stream about 100 frames plus 8 upstream reads, read from source; measured on Windows loopback, the flow stopped at `chunks=152` on 16 KB frames and 464 on ordinary frames. `SlowClientStreamTest` proves a plateau under 4096 frames of 16 KB, not the figure. A client that stops reading meets `ClientStallHandler`, an `IdleStateHandler` with `observeOutput`, ticking at a quarter of `client-write-timeout`: with bytes pending and no write progress for that long it closes the channel, 1 to 1.5 timeouts after the last progress, and the normal cancel path runs (`StalledClientStreamTest`, a second request on a kept-alive connection included, so `doOnConnection` is enough). Queue time does not count, and with nothing pending it never closes, so a slow provider is not a stall. Progress is seen only as the kernel takes bytes, so the connection, and only the connection, is safe down to one kernel step per timeout. On Windows loopback, at a 1 s timeout with idle 60 s, 85.6 KB/s held and 70.8 KB/s was cut; at the 30 s default the edge is 2 to 3 KB/s, and which side a client lands on varies with its buffer. This applies to ordinary frames too: a token stream has no backlog only for a client above the provider's rate, and a 984 B/s client on a 13 KB/s stream had 40888 bytes pending when it was cut at 76.8 s, while 2.9 KB/s completed.
- **A slow reader and `idle-timeout`.** Above the stall limit a slow reader can still lose the stream: `Flux.timeout` cannot see demand, so a live client too slow to pull an upstream read within `stream.idle-timeout` ends on it (`StreamStarvedUpstreamTest`). At the defaults that means below about 11.8 to 19.7 KB/s on 16 KB frames, a bracket measured on Windows loopback; the edge scales with frame size, by inference, since the demand refill of about 64 frames must drain inside `idle-timeout`. It moves with `idle-timeout`: at 2 s, 146 KB/s got the frame, so "160 KB/s held" is true only with a larger idle. The client gets an `upstream_timeout` frame, and an ERROR `Stream failed` and the adapter's WARN blame a healthy provider. It learns late, behind the backlog: at 7.9 KB/s the server failed at 106 s and the frame arrived at 305 s. RTE-02 must not count such a failure against the provider; GW-08 is the fix ([gotcha](../gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout.md)).
- **Disconnect.** Mid-stream, `MonoSendMany`'s `closeFuture` listener cancels its source and completes the write `Mono` normally, so `onErrorResume` sees nothing ([gotcha](../gotchas/reactor-netty-close-completes-the-write-mono-normally.md)); before the first frame, `ChannelOperations.terminate` cancels the chain. A first frame between the two fails at once, and `ChannelSendOperator` keeps its source, so `SseBodyInserter` ends the flow itself: `takeUntilOther` on a sink that `doFinally` fires ([bug](../bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running.md), `ChatStreamClosingChannelTest`). The flow's cancellation reaches the upstream `Flux`, and `FluxReceive` closes the connection. One INFO `Stream cancelled` line carries the request ID. `ChatStreamLog` also gives DEBUG `Stream completed` and ERROR `Stream failed` (after commit only).
- **`warmUp()`** runs from the bean method, at startup, and does what the first requests would otherwise do on an event loop: `ReactiveFlowKt`'s `ServiceLoader` read, Jackson's first tree read and write, `HttpClient.warmup()`. The tree read and write use Boot's `JsonMapper`, which `ChatHandler` shares.
- **The pool.** One named `ConnectionProvider` (`openai`) per adapter, HTTP/1.1, `disableRetry(true)` (Reactor Netty otherwise repeats a request once on some connection resets; keep it, as no test can go red without it: JDK NIO reports "Connection reset", which Netty's retry condition does not match; so a provider that closes a pooled connection without `Connection: close` costs the next client a 502, [gotcha](../gotchas/pooled-connection-closed-without-connection-close-costs-a-502.md)), `pendingAcquireMaxCount` twice `max-connections`, eviction every 30 s. `close()` disposes it. The client's event loops (`reactor-http-*`) are not the server's (`webflux-http-*`), so a response crosses threads once.
- **Usage.** `ChatCompletionService` keeps the provider's `Usage`, or asks `UsageEstimator` (length over 4 per text, 3 per message, 3 for the reply; an opaque part counts 0). `ChatHandler` adds `x-gateway-usage-estimated: true`; the body stays OpenAI-shaped. It writes bytes, not a tree, because `JacksonJsonEncoder` logs the value it encodes.
- **Exposure, until GW-04.** `server.address` defaults to `127.0.0.1`. `ExposureGuard` stops the start on a non-loopback address unless `llmgw.security.allow-unauthenticated-exposure=true`. `LoopbackHostWebFilter`, right after `RequestContextWebFilter`, gives 403 to anything but `<loopback name>` or `<loopback name>:<digits>` in `Host`, because a DNS-rebinding page still sends its own ([bug](../bugs/grafana-anonymous-admin-reachable-through-dns-rebinding.md)). A cross-site POST is stopped by the required Content-Type (415 for a `no-cors` POST). A CORS preflight gets 200 from Spring with no `Access-Control-*` header, so a browser does not send the JSON POST.
- **What typed members change** (GW-01 section 4, "Narrower than OpenAI"). Request: an absent `content` goes upstream as `"content":null`. Response: an absent `content` or `finish_reason` becomes `null`, a `finish_reason` that is not a string becomes `null`, an `index` that is not an integer becomes `0`. Both ways: a number keeps its value, not its spelling (`1e5` becomes `1E+5`), and `-0.0` becomes `0.0`. `ChatRequestReaderTest`, `OpenAiResponseReaderTest`, `ChatCompletionsEndToEndTest`.

## Failure behaviour

Fail-closed: there is no answer to give.

- Provider slow: `request-timeout` (300 s) bounds the whole call, then 504 `upstream_timeout`; the subscription is cancelled, so the connection closes.
- Provider down: connect 5 s, TLS handshake 10 s, DNS query 5 s, pool acquire 5 s, each 503 `provider_unavailable`. Nothing was sent, so a fallback may repeat the call.
- Connection reset or closed after the write, a reply that is not HTTP, headers over 8 KB: 502 `upstream_error`. The provider may have the call.
- Provider 401 or 403: 502 `upstream_auth_failed` with `x-should-retry: false`; the client's key is not the problem.
- Provider quota: 503 `upstream_quota_exhausted` with `x-should-retry: false`.
- Client disconnects: the coroutine, the Mono and the upstream connection are cancelled. The provider may still bill the partial call; COST-02 owns that. `StreamSummary` holds what is known of a stream.
- Provider silent on a stream: `stream.first-byte-timeout` (120 s) gives 504 `upstream_timeout` before commit; `stream.idle-timeout` (60 s) and `stream.total-timeout` (30 m) give 504 before commit, an `upstream_timeout` frame after it. A provider that holds the body open after `[DONE]` costs at most the 1 s drain, silently, and that connection closes instead of returning to the pool.
- 2xx with no chunk, or a first event that cannot be read: 502 `upstream_error` before commit. An in-band error first: the mapped row, by status.
- Provider closes without `[DONE]`: complete if a `finish_reason` was seen, and the gateway writes `[DONE]`; otherwise `upstream_error`.
- Malformed event, in-band error, reset, premature close, or an event over `stream.max-event-size` after commit: one error frame, no `[DONE]`, a clean end; the rest is not read. The provider's in-band `message` is never relayed.
- Client slow: back-pressure to the provider, and nothing is dropped above the limit of `idle-timeout`, about 11.8 to 19.7 KB/s at the defaults on 16 KB frames. Below it the stream ends with an `upstream_timeout` frame, behind the backlog, with an ERROR `Stream failed` and the adapter's WARN against a healthy provider (How it works).
- Client stops reading: bytes pending and no write progress for `client-write-timeout`, so the connection closes and the stream ends `cancelled`, with one INFO from `ClientStallHandler` carrying the connection ID and no request ID: on a stream `Stream cancelled` follows and the two share no key, and a non-stream cut leaves only that line (OBS-03). A stream that fits the queues ends first: `completed` means handed to Netty, not read by the client, so a stalled client can be logged `Stream completed` (DEBUG) while it lost the tail; the provider did finish, so usage is right (smoke finding 3).
- A bug in the flow after commit: one ERROR with the stack trace, `internal_error` frame, clean end. Only what the flow cannot catch reaches the mapper's committed branch.
- Response over `max-response-size`: 502 `upstream_error`, never 413.
- Provider 2xx with an unreadable body: 502 `upstream_error`; the body is not logged.
- Provider usage missing or unreadable: estimated and flagged; the call succeeds.
- Provider key absent, or with a character outside visible ASCII such as a trailing newline: the app does not start.
- Graceful shutdown: Boot's default, up to 30 s per phase; a longer in-flight call is cut until OPS-03.

## Configuration

`gateway-app/src/main/resources/application.yaml`, bound to `OpenAiProperties`. `toAdapterConfig()` validates it from the bean method and names properties only.

| Property | Default | Note |
|---|---|---|
| `server.address` | `${LLMGW_BIND_ADDRESS:127.0.0.1}` | ADR-GW-01-007 |
| `spring.http.codecs.max-in-memory-size` | `10MB` | Request body limit |
| `llmgw.security.allow-unauthenticated-exposure` | `false` | Removed by GW-04 |
| `llmgw.providers.openai.base-url` | `https://api.openai.com/v1` | `http` or `https`, a host, no user info; `LLMGW_PROVIDERS_OPENAI_BASE_URL` |
| `llmgw.providers.openai.api-key` | `${OPENAI_API_KEY:}` | Required, from the environment only |
| `llmgw.providers.openai.connect-timeout` | `5s` | > 0 |
| `llmgw.providers.openai.request-timeout` | `300s` | > 0, whole call, non-stream only |
| `llmgw.providers.openai.max-response-size` | `16MB` | 1 KB to 256 MB; on a stream, an error body only |
| `llmgw.providers.openai.pool.max-connections` | `500` | A call holds one for its whole duration, and a stream for up to `total-timeout`, so this is the ceiling on concurrent streams |
| `llmgw.providers.openai.pool.pending-acquire-timeout` | `5s` | |
| `llmgw.providers.openai.pool.max-idle-time` | `30s` | At most the server's |
| `llmgw.providers.openai.pool.max-life-time` | `5m` | Picks up DNS changes; a stream outlives it (`OpenAiChatStreamIntegrationTest`) |
| `llmgw.providers.openai.stream.first-byte-timeout` | `120s` | > 0, at most `total-timeout`; to the first upstream byte or error status |
| `llmgw.providers.openai.stream.idle-timeout` | `60s` | > 0, at most `total-timeout`; between upstream buffers, so it also runs while a slow client holds the next read back |
| `llmgw.providers.openai.stream.total-timeout` | `30m` | > 0 |
| `llmgw.providers.openai.stream.max-event-size` | `1MB` | 1 KB to 16 MB, per event, counted over its lines without line breaks |
| `llmgw.server.client-write-timeout` | `30s` | > 0, below `stream.idle-timeout`; how long a client's socket may take none of the bytes pending for it, on every response of this server |

The `local` profile (`application-local.yaml`) points at `http://127.0.0.1:8090/v1` with a fixed fake key, so a real `OPENAI_API_KEY` in the shell never reaches the mock. `application.properties` in `gateway-app/src/test/resources` and `gateway-app/src/integrationTest/resources` gives every test context a fake key and a closed local port as the provider; `MockBackedTest` replaces both with the mock and its canary key.

## Decisions

- [ADR-018](../../DECISIONS.md#adr-018): chat domain model and JSON handling.
- [GW-01 spec](../../specs/GW-01-chat-completions.md), ADR-GW-01-001 to -012: typed core with extras, Jackson's tree model, `coRouter`, WebClient with one deadline, failure mapping, pass-through resolver, the exposure stopgap, request limits, adapter test strategy, keeping provider types out, the estimate, key handling.
- [ADR-019](../../DECISIONS.md#adr-019): the streaming contract.
- [GW-02 spec](../../specs/GW-02-sse-streaming.md), ADR-GW-02-001 to -013: port shape and commit point, chunk translation, the SSE parser, frames, usage on a stream, deadlines, failure after commit, cancellation and buffers, back-pressure, stream headers, limits, test strategy, architecture rules.
- [ADR-004](../../DECISIONS.md#adr-004), [ADR-015](../../DECISIONS.md#adr-015).

## Gotchas

- `DataBufferLimitException` is the adapter's to translate: a bare one that reaches the HTTP edge is a 500. On the request side `readJsonObject` translates its own ([bugs/gateway-body-limit-on-functional-route-gives-500](../bugs/gateway-body-limit-on-functional-route-gives-500.md)).
- Wait with `Mono.timeout`, never `withTimeout`: its continuation lands on a thread BlockHound does not watch ([gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay](../gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)).
- With `USE_BIG_DECIMAL_FOR_FLOATS`, a float past `BigDecimal`'s range (`1e99999999999`) raises a bare `NumberFormatException` quoting the literal, not a `JacksonException`, so a catch of `JacksonException` alone gives a client a 500 and puts the value in the log. Read through `readTreeOrNull`.
- Jackson builds its tree deserializer on first use under a `ReentrantLock`, and concurrent first requests park on it on an event loop; BlockHound fails that. `warmUp()` reads and writes a tree first. A new first-use path in another `JsonMapper` needs the same.
- `ReactorClientHttpConnector` implements `SmartLifecycle`, and `spring-webflux` declares `spring-context` optional, so the module depends on `spring-context` although it wires nothing.
- `HttpHeaders.getContentType()` throws `InvalidMediaTypeException` on an unparsable value; `readJsonObject` turns that into 415. WebTestClient refuses to send such a value, so the test uses a plain client.
- In the `integrationTest` suite `src/test/resources` is not on the classpath, so the suite has its own `application.properties` ([gotchas/integration-test-suite-has-no-test-resources](../gotchas/integration-test-suite-has-no-test-resources.md)).
- A `@DynamicPropertySource` wins over `@TestPropertySource`, and a superclass's runs last, so `ClosedProviderPortTest` cannot extend `MockBackedTest` and a subclass cannot change its key ([gotchas/dynamic-property-source-superclass-registers-last](../gotchas/dynamic-property-source-superclass-registers-last.md)).
- With `logging.level.web=TRACE`, Spring logs a `String` body in full, on the client side too. `NoLeakIntegrationTest` shares its JVM and log with its own clients, so `MockBackedTest` sends and reads bytes.
- A non-stream client disconnect leaves no gateway log line with the request ID, and the WARNs from `FluxReceive` and `HttpClientConnect` carry none, so a call the provider may bill cannot be found from the gateway log alone (OBS-03, COST-02). A stream has its `Stream cancelled` line.
- A client RST leaves `FluxReceive`'s WARN `An exception has been observed post termination`, with the peer port and no request ID; a true FIN does not, and a socket closed with unread bytes sends RST, so most busy-stream disconnects do (smoke finding 4). It remains: silencing the logger would hide real errors. `Stream cancelled` gives the ID, and matching the two by connection is OBS-03's.
- An HTTP/1.0 client gets `HTTP/1.1 200 OK` with `transfer-encoding: chunked` and `connection: close`, which it cannot decode: Reactor Netty's behaviour (smoke finding 6).
- A client that half-closes (`shutdown(SHUT_WR)`) and keeps reading is treated as gone: `client_cancelled` within 17 ms. Reactor Netty's default (smoke finding 7).
- `ClientStallHandler` spares a slow reader, and `idle-timeout` then ends it with an `upstream_timeout` for a healthy provider ([gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout](../gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout.md)).
- A chunk with empty `choices` and no `usage` is forwarded with its extras; only the one carrying `usage` is dropped for a client that did not ask.
- `Flux.timeout(Publisher, Function)` has no `Duration` form with a factory: both arguments are publishers, so each item schedules a `Mono.delay` on `parallel`.
- Inside `Socket().apply { }`, `port` is the socket's own, 0: an integration test hits `BindException`.
- `Host: [::1` is refused by Reactor Netty with an empty 400 before the filter runs: no envelope and no `x-request-id`.
- A good TLS handshake, real completions, streams and real 400, 401 and 404 bodies are proven live ([run](../runs/2026-11-04-live-openai.md)). Real in-band stream errors, 429 and 5xx bodies are not: they cannot be provoked on demand.
