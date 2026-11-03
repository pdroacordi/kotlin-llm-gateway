---
id: bugs/error-mapper-disconnect-check-turns-errors-into-empty-200
type: bug
title: An error whose cause chain quotes "broken pipe" or "connection reset by peer" is answered 200 with an empty body
status: active
severity: high
tags: [errors, webflux, openai-compat, disconnect]
related: [gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain, systems/request-context-and-errors, patterns/one-error-funnel]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandlerTest.kt
---

## Summary

`GatewayErrorWebExceptionHandler` treats any exception that `DisconnectedClientHelper.isClientDisconnectedException` accepts as a client disconnect and returns `Mono.empty()` without writing. That helper matches message phrases anywhere in the cause chain, so a request error whose message quotes client text, or an untranslated upstream I/O error, gets 200 OK with an empty body instead of the envelope.

## Symptom

Found in the FND-04 review (Spring Framework 7.0.10). `DisconnectedClientHelper.isClientDisconnectedException` returns `true` for:

- `ServerWebInputException("Failed to read HTTP message", null, DecodingException("JSON decoding error: Cannot deserialize value of type `int` from String \"connection reset by peer\" ..."))`, which is what a typed request body (GW-01) produces when a client sends `"max_tokens": "connection reset by peer"`.
- `IllegalStateException("adapter", IOException("Broken pipe"))`, an upstream connection failure an adapter did not translate.

Repro: call the helper on the two exceptions above with `spring-web-7.0.10.jar` on the classpath. FND-04's own routes do not expose a trigger today: `Accept: broken pipe` on `/actuator/health` gives the 406 envelope, because the produces condition replaces the parse error with a generic `NotAcceptableStatusException`. The bug is live as soon as GW-01 adds a typed body or a provider adapter.

## Root cause

`GatewayErrorWebExceptionHandler.kt:47-50` returns `Mono.empty()` for a "disconnected" exception, which marks it handled. `HttpWebHandlerAdapter.handle` then runs `response.setComplete()`, which commits the response with the status still unset, so Reactor Netty sends `200 OK` with no body (only `x-request-id`). The helper checks lowercase message phrases on every cause, not the connection state (see the gotcha).

`GatewayErrorWebExceptionHandlerTest` "a disconnected client gets nothing written" asserts the message-based classification with `IOException("Connection reset by peer")`.

## Fix

Fixed in FND-04, in `gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt`:

- `:48-49`: the helper's answer on the original exception is kept as `looksDisconnected`, and before commit the handler always calls `writeReply`, which translates and writes the envelope. `looksDisconnected` only lowers the log level: a 5xx at DEBUG instead of ERROR (`logReply`, `:125`), and after commit DEBUG instead of ERROR (`:53-57`).
- `:78`: a failure of the envelope write that the helper attributes to the client is logged at DEBUG and completes; any other write failure still goes to `writeFallback` (`:88`). The helper is applied to the write failure, not to the original exception, so an exception that only looks like a disconnect followed by a real codec failure still gets the constant 500 body.
- FND-04 section 5 (handler steps 1, 2, 5, 6 and the failure-model row "Client disconnected") and the systems entry's contract rule 8 describe the new behaviour.

The Symptom and Root cause lines refer to the file before the fix. Commit: the FND-04 commit (`Roadmap: FND-04`).

## Prevention

Added for FND-04, both red before the fix:

- `GatewayErrorWebExceptionHandlerTest:92` "a decoding error quoting broken pipe still gets the 400 invalid_body envelope": `ServerWebInputException(..., DecodingException("... \"broken pipe\""))` gives 400 `invalid_body` (before: status null, nothing written).
- `GatewayErrorWebExceptionHandlerTest:103` "a write that fails because the client is gone completes without an ERROR log": the write fails with `IOException("Broken pipe")` and no ERROR line is logged.

They replace "a disconnected client gets nothing written", which asserted the bug. Review rule: a `WebExceptionHandler` that returns `Mono.empty()` without setting a status produces a 200; a heuristic about the connection may choose a log level, never whether to write.

## Revision (2026-11-03)

The fix stopped the empty 200 but left the helper deciding two other things it cannot know. Found in the second-pass review.

1. **A 5xx to a live client is logged only at DEBUG, without the throwable** (`GatewayErrorWebExceptionHandler.kt:125-131`). `IllegalStateException("adapter", IOException("Connection reset by peer"))` gives `looksDisconnected = true`; the client receives the 500 with its request ID, and at the default level nothing is logged. `HttpWebHandlerAdapter` is `off`, so there is no other line. The helper exempts only `RestClientException`, `WebClientException`, `DataAccessException` and `MessagingException` (bytecode, spring-web 7.0.10), so a Lettuce connection error, a JDK client failure or any class named `EOFException` in the chain is silent. A successful write proves the client was there, which is the opposite of what the log level assumes. No test covers a 5xx here: `GatewayErrorWebExceptionHandlerTest:92` is a 400 and `:103` a 404.
2. **After commit such an exception does not abort the connection** (`:53-59`). `Mono.error(ex)` reaches `HttpWebHandlerAdapter.handleUnresolvedError`, which treats it as a client disconnect, and the response then ends normally. Reproduced on a real `HttpServer`: after one flushed chunk, an error with cause `IOException("Connection reset by peer")` gave the client `200` and a clean end of body. FND-04 section 5 and the systems entry say the connection is closed and the client sees a truncated response.

Fixed in the second-review fix commit (`Roadmap: FND-04`):

- Before commit the level comes from the write: the reply is logged after it (`GatewayErrorWebExceptionHandler.kt:66`), a 5xx at ERROR with `LoggableFailure.of(ex)`, unless the write itself failed with an exception the helper attributes to the client (`:71`), which is the only DEBUG path. `looksDisconnected` is gone. The "Client disconnected" row and handler steps of FND-04 section 5 are amended.
- After commit the mapper always logs ERROR and returns the stackless replacement described in [bugs/committed-response-error-logged-twice-with-query-string](committed-response-error-logged-twice-with-query-string.md) (`:49-50`); its message holds no phrase, so the adapter propagates it and Netty closes the connection. The mock's mapper does the same and decides intentional aborts by type only.

Prevention, each red before the fix: `GatewayErrorWebExceptionHandlerTest:98`, `:119` and `:130`; `CommittedResponseIntegrationTest:39` and `MockCommittedResponseIntegrationTest:72`, where the client read a clean `0` chunk.

Review rule, extended: a phrase heuristic may lower a log level only when something else confirms the client is gone.
