---
id: patterns/upstream-bytes-then-pure-classification
type: pattern
title: An adapter reads status, headers and raw bytes on one path, then a pure function decides success or which sealed failure
status: active
tags: [adapters, webclient, errors, testing, provider]
related: [patterns/one-error-funnel, patterns/typed-core-with-json-extras, systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiFailureClassifier.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiResponseReader.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
---

## Summary

An outbound call has many failure shapes: statuses, odd bodies, transport exceptions. The adapter does no interpretation on the reactive path. It collects `(status, headers, bytes)` for every status, and pure functions turn that into a result or a sealed failure. The table of failures is then unit-tested with no socket.

## Details

Canonical use: GW-01 spec, section 5.

- **One path for every status.** `exchangeToMono { it.toEntity<ByteArray>() }`, not `retrieve()` with `onStatus`. No `WebClientResponseException` carries a provider body into an exception message, and from there into a log.
- **One deadline.** `.timeout(requestTimeout)` on the Mono, outside WebClient, so the only top-level `TimeoutException` is ours; then `awaitSingle()`.
- **Pure classifier.** `classify(status, headers, bytes, now): ProviderFailure`, tolerant of a non-JSON body, such as a proxy's HTML 502. The clock is a parameter, for HTTP-date `retry-after`.
- **Pure reader.** `read(bytes): Completed?`; `null` means unreadable, which is an upstream failure.
- **Transport failures are a second small function,** exception to failure, by named types. Anything unknown propagates as a bug. `CancellationException` is never caught.
- **Failures carry no `cause`.** The adapter logs its own line with chosen fields, inside the coroutine so MDC holds the request ID.

## Why

- The failure table is the part most likely to be wrong and the part with the most cases. As a pure function each case is one line of a parameterized test, with no Docker and no timing.
- Exceptions built by HTTP clients tend to hold URLs, bodies and headers. Keeping them off the expected path keeps those out of logs.
- Public basis: `exchangeToMono` and codec limits in the Spring WebClient reference (https://docs.spring.io/spring-framework/reference/web/webflux-webclient/client-builder.html). The split itself is this project's design.

## Gotchas / pitfalls

- The body is buffered, so this fits non-streaming calls. A stream classifies status and headers the same way, then translates chunk by chunk, and never buffers.
- The in-memory limit raises `DataBufferLimitException` on this path: the adapter translates it. A bare one reaching the HTTP edge is a 500.
- `toEntity` parses the response's `Content-Type` even for bytes, and a value it cannot parse throws, so the adapter removes the header first ([bug](../bugs/adapter-bad-provider-content-type-gives-500.md)).
- Do not log the bytes on a parse failure, or the exception's message: a provider body can hold completion text, and Jackson can quote source bytes.
- A real socket is still needed for what a pure function cannot show: which exception a fault really raises, and that cancellation closes the connection.
