---
id: bugs/adapter-bad-provider-content-type-gives-500
type: bug
title: A provider Content-Type that cannot be parsed gives a 500 internal_error, on any status, and throws a paid 200 away
status: active
severity: medium
tags: [adapters, webclient, errors, openai]
related: [systems/provider-adapters, patterns/upstream-bytes-then-pure-classification, gotchas/spring-http-headers-content-type-throws-on-bad-header]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
---

## Summary

When a provider or a proxy in front of it sends a `Content-Type` Spring cannot parse, `OpenAiChatAdapter.complete` throws `InvalidMediaTypeException` instead of returning a `ProviderResult`. The client gets 500 `internal_error`, not a section 4 row, and the ERROR log holds the header value.

## Symptom

Found in the GW-01 review with a raw-socket stub and the real adapter, on Windows and on Linux (epoll). Each of these raised `InvalidMediaTypeException` out of `complete`:

- `200` with a valid completion and `Content-Type: garbage`.
- `500` with `Content-Type: text/html; charset=`.
- `502` with `Content-Type: text/html; charset=no-such-charset`.

No test covers it: the suites only see valid types.

## Root cause

`it.toEntity(ByteArray::class.java)` (`OpenAiChatAdapter.kt:128`) goes through `BodyExtractors.readWithMessageReaders`, which calls `HttpHeaders.getContentType()`, and that throws on an unparsable value. `InvalidMediaTypeException` is an `IllegalArgumentException`, which is none of the types `exchange` catches (`OpenAiChatAdapter.kt:100-113`), so it leaves the port. This breaks `ChatModelPort`'s "never throws for an expected failure" and the section 4 row "any other status outside 2xx". The FND-04 side of the same behaviour is handled for requests in `RequestBodies.kt:33-38`, not for responses.

## Fix

`OpenAiChatAdapter.kt:128` reads the body through `withoutContentType` (`:135`), which drops the header first. The body is read as bytes whatever the type says, so a 200 keeps its completion and any other status maps by status, with the other headers intact for the classifier.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`RawProviderSocketIntegrationTest`: `Content-Type: garbage`, `text/html; charset=` and an unknown charset on a 200 and on a 500, and on a 429 that keeps its code and `retry-after`; all seven were red before the fix. Review rule: list what a body read can raise from headers, not only from the body.
