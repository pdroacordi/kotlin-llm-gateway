---
id: bugs/completion-text-in-spring-encoder-debug-log
type: bug
title: With Boot's web logger group at DEBUG, the first 100 characters of every completion reach the log
status: active
severity: medium
tags: [logging, secrets, webflux, jackson]
related: [systems/provider-adapters, gotchas/spring-jackson-encoder-logs-the-value-at-debug]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatHandler.kt
---

## Summary

`ChatHandler` hands an `ObjectNode` to the codec, and Spring's `JacksonJsonEncoder` logs the value it encodes. `logging.level.web=debug`, a usual first step when debugging a gateway, puts completion text in the log (all of it at TRACE). CLAUDE.md, Observability: completion text is never a log field unless the team opted in.

## Symptom

Found in the GW-01 review with a standalone encoder probe, Spring 7.0.10:

`DEBUG org.springframework.http.codec.json.JacksonJsonEncoder -- Encoding [{"content":"canary-completion-canary-completion-... (truncated)...]`

`NoLeakIntegrationTest` does not see it: it raises only `io.github.pdroacordi.llmgateway` to DEBUG. Requests are safe, because they are read as bytes, as is the adapter's upstream body: a whole-adapter run with the root logger at DEBUG held no prompt, completion or key.

## Root cause

`response.bodyValueAndAwait(ChatResponseWriter.write(...))` (`ChatHandler.kt`, `ok`) sends a tree through `AbstractJacksonEncoder`, whose `logValue` prints `LogFormatUtils.formatValue(value, ...)` unless `Hints.SUPPRESS_LOGGING_HINT` is set. Boot's `web` group includes `org.springframework.http`.

## Fix

`ChatHandler.kt:42` writes `mapper.writeValueAsBytes(tree)`, so `ByteArrayEncoder` handles the body and logs only a size.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`NoLeakIntegrationTest` sets `logging.level.web=TRACE` and asserts a completion canary is absent; red first with `the completion in the log`, from `JacksonJsonEncoder`'s `Encoding [...]` line. GW-02 must not write chunks through a Jackson encoder either.
