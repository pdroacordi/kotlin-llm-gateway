---
id: systems/provider-adapters
type: system
title: Provider adapters: the OpenAI adapter behind ChatModelPort, with every upstream failure a sealed value
status: active
tags: [adapters, openai, openai-compat, webclient, errors, json, blockhound]
related: [patterns/typed-core-with-json-extras, patterns/upstream-bytes-then-pure-classification, patterns/one-error-funnel, systems/request-context-and-errors, systems/mock-provider, gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay, bugs/gateway-body-limit-on-functional-route-gives-500, bugs/grafana-anonymous-admin-reachable-through-dns-rebinding]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/json/JsonValue.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatModelPort.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatCompletionService.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/UsageEstimator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiRequestWriter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiResponseReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiFailureClassifier.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/RetryAfter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/OpenAiProperties.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/security/LoopbackHostWebFilter.kt
---

## Summary

`gateway-providers` holds the OpenAI adapter behind `ChatModelPort`. `POST /v1/chat/completions` reaches `ChatHandler`, `ChatCompletionService` resolves the deployment and calls the port, and the adapter makes one upstream POST with the configured key. It hands status, headers and bytes to pure functions, which give a `ProviderResult`. Any upstream failure is a sealed `ProviderFailure`, which the mapper renders as one row of GW-01 section 4. A slow or absent provider costs at most `request-timeout`, and nothing is retried. Contract: [GW-01 spec](../../specs/GW-01-chat-completions.md), section 4.

## Contract (never break)

1. Provider types never cross the port. A stream is translated chunk by chunk, never buffered whole. Usage comes from the provider; an estimate is used only when the provider omits it, and is flagged `estimated`. ARCH-1, ARCH-12, `internal` visibility, `ChatCompletionServiceTest`; the stream clause is GW-02's.
2. Every outbound call has a connect timeout and a whole-call deadline. `OpenAiChatAdapterIntegrationTest` (`hang` with a 500 ms deadline).
3. An adapter never retries; retries and fallback are RTE-02's, before the first byte. `OpenAiChatAdapterIntegrationTest`: one failed call, one journal entry.
4. A provider's `message` is never relayed or logged; only `code` and `param` that pass the regex are. `OpenAiFailureClassifierTest`, `NoLeakIntegrationTest`.
5. The provider key is never logged or returned; the client's credential is never forwarded. `ApiKeyTest`, `StartupFailureTest`, `NoLeakIntegrationTest`, the journal fingerprint in `ChatCompletionsEndToEndTest`.
6. Provider response headers reach the client only through `retry-after` and `retry-after-ms`. `UpstreamErrorMappingTest`.
7. Members the gateway does not model pass through verbatim in both directions. `OpenAiShapeContractTest`, `ChatCompletionsEndToEndTest`.
8. A cancelled caller cancels the upstream call; `CancellationException` is never caught. `ClientDisconnectIntegrationTest`, `OpenAiChatAdapterIntegrationTest`.

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

  Measured through WebClient 7.0.10 against the mock: a closed port and a full pool raise `WebClientRequestException`, `hang` past the deadline a top-level `TimeoutException`, `disconnect` after the headers a bare `PrematureCloseException`, a response over the limit a bare `DataBufferLimitException`. Against a raw socket (`RawProviderSocketIntegrationTest`): a reset after the write, a reply that is not HTTP and headers over 8 KB raise `WebClientRequestException` with no "nothing sent" cause, so all are 502; a provider `Content-Type` that cannot be parsed raised `InvalidMediaTypeException` from the body read until the adapter dropped the header.
- **`warmUp()`** runs from the bean method, at startup, and does what the first requests would otherwise do on an event loop: `ReactiveFlowKt`'s `ServiceLoader` read, Jackson's first tree read and write, `HttpClient.warmup()`. The tree read and write use Boot's `JsonMapper`, which `ChatHandler` shares.
- **The pool.** One named `ConnectionProvider` (`openai`) per adapter, HTTP/1.1, `disableRetry(true)` (Reactor Netty otherwise repeats a request once on some connection resets; keep it, as no test can go red without it: JDK NIO reports "Connection reset", which Netty's retry condition does not match), `pendingAcquireMaxCount` twice `max-connections`, eviction every 30 s. `close()` disposes it. The client's event loops (`reactor-http-*`) are not the server's (`webflux-http-*`), so a response crosses threads once.
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
- Client disconnects: the coroutine, the Mono and the upstream connection are cancelled. The provider may still bill the partial call; COST-02 owns that.
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
| `llmgw.providers.openai.request-timeout` | `300s` | > 0, whole call |
| `llmgw.providers.openai.max-response-size` | `16MB` | 1 KB to 256 MB |
| `llmgw.providers.openai.pool.max-connections` | `500` | A non-stream call holds one for its whole duration |
| `llmgw.providers.openai.pool.pending-acquire-timeout` | `5s` | |
| `llmgw.providers.openai.pool.max-idle-time` | `30s` | At most the server's |
| `llmgw.providers.openai.pool.max-life-time` | `5m` | Picks up DNS changes |

The `local` profile (`application-local.yaml`) points at `http://127.0.0.1:8090/v1` with a fixed fake key, so a real `OPENAI_API_KEY` in the shell never reaches the mock. `application.properties` in `gateway-app/src/test/resources` and `gateway-app/src/integrationTest/resources` gives every test context a fake key and a closed local port as the provider; `MockBackedTest` replaces both with the mock and its canary key.

## Decisions

- [ADR-018](../../DECISIONS.md#adr-018): chat domain model and JSON handling.
- [GW-01 spec](../../specs/GW-01-chat-completions.md), ADR-GW-01-001 to -012: typed core with extras, Jackson's tree model, `coRouter`, WebClient with one deadline, failure mapping, pass-through resolver, the exposure stopgap, request limits, adapter test strategy, keeping provider types out, the estimate, key handling.
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
- A client disconnect leaves no gateway log line with the request ID, and the WARNs from `FluxReceive` and `HttpClientConnect` carry none, so a call the provider may bill cannot be found from the gateway log alone (OBS-03, COST-02).
- `Host: [::1` is refused by Reactor Netty with an empty 400 before the filter runs: no envelope and no `x-request-id`.
- TLS and OpenAI's real error bodies are unproven: the mock is plain HTTP and imitates them; a live call needs the user's go-ahead. `ByteBuf` release has not run under a leak detector.
