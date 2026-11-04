---
id: gotchas/spring-jackson-encoder-logs-the-value-at-debug
type: gotcha
title: Spring's Jackson encoder logs the value it encodes at DEBUG, and all of it at TRACE; the byte array encoder logs a size
status: active
tags: [spring, webflux, logging, secrets]
related: [bugs/completion-text-in-spring-encoder-debug-log, bugs/unknown-route-message-echoes-the-path-into-the-debug-log, systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatHandler.kt
---

## Summary

Spring 7.0.10's `JacksonJsonEncoder` logs `Encoding [<value>]` under `org.springframework.http.codec.json`, cut at 100 characters at DEBUG and uncut at TRACE. Boot's `web` logger group includes `org.springframework.http`, so `logging.level.web=debug` turns it on. `toString` overrides on domain types do not help when the value is a `JsonNode`.

## Details

Probed with an `ObjectNode`: `Encoding [{"content":"canary-completion-canary-... (truncated)...]`. The same bytes through `ByteArrayEncoder`: `Writing 374 bytes`. WebClient's `enableLoggingRequestDetails` does not govern this line; `Hints.SUPPRESS_LOGGING_HINT` does.

## How to handle it

For a body that can hold prompt or completion text, serialise first and write `ByteArray`, as `OpenAiChatAdapter.kt:118` and `ChatHandler.kt:42` do.

## Revision (2026-11-04)

The error envelope goes through this encoder too, so anything a message quotes reaches this line: a key in the path of an unknown route did ([bug](../bugs/unknown-route-message-echoes-the-path-into-the-debug-log.md)). A message therefore holds no request content, rather than the mapper writing bytes.
