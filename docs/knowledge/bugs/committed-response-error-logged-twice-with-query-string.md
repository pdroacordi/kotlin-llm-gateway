---
id: bugs/committed-response-error-logged-twice-with-query-string
type: bug
title: An error after commit is logged twice; Spring's second ERROR line carries the query string and exception text without the request ID
status: active
severity: low
tags: [errors, logging, webflux, streaming]
related: [systems/request-context-and-errors, bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/main/resources/application.yaml
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ErrorEnvelopeIntegrationTest.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/CommittedResponseAbort.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/CommittedResponseIntegrationTest.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorWebExceptionHandler.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockCommittedResponseIntegrationTest.kt
---

## Summary

On a committed response, the mapper logs ERROR and returns `Mono.error(ex)` so that the connection is aborted. `HttpWebHandlerAdapter.handleUnresolvedError` then logs a second ERROR line, `Error [<ex.toString()>] for HTTP GET "/path?query", but ServerHttpResponse already committed (200 OK)`. That line is written outside the MDC, so it has no `requestId`, and it records the raw query string, which FND-04 section 5 lists under "Never recorded".

## Symptom

Found in the FND-04 review by reading the `spring-web-7.0.10.jar` bytecode. `HttpWebHandlerAdapter.formatRequest` appends `?` plus `getURI().getRawQuery()`, and the committed branch calls `Log.error` with it. This is the GW-02 mid-stream path, so no FND-04 test reaches it over HTTP.

## Root cause

`GatewayErrorWebExceptionHandler.kt:53-56` returns the original exception, and Spring's adapter logs every unresolved error it receives.

## Fix

Fixed in FND-04 with the second option: `gateway-app/src/main/resources/application.yaml:25` sets `logging.level.org.springframework.web.server.adapter.HttpWebHandlerAdapter: off`, with a comment linking this entry (`:23-24`). The mapper logs every error with the request ID and writes the envelope for every exception before commit, so that logger only ever repeated lines. The mapper still returns `Mono.error(ex)` after commit (`GatewayErrorWebExceptionHandler.kt:59`), so the connection is aborted. The Root cause line refers to the file before the fix. Commit: the FND-04 commit (`Roadmap: FND-04`).

Switching this logger off was not enough to keep the query string out of the log: the mapper's own ERROR line printed it through the Reactor checkpoint that Spring attaches to every error, fixed separately ([bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint](mapper-error-log-carries-raw-query-via-reactor-checkpoint.md)).

GW-02 confirms over a real SSE stream that the committed path logs one line without the query string.

## Prevention

Added for FND-04: `ErrorEnvelopeIntegrationTest:157` "an error after commit gives one ERROR line, with the request ID and no query string" sends `/test-probe/committed?secret-query=...` through the real `HttpHandler` bean with a mock exchange; the probe endpoint commits the response, then fails. It asserts exactly one unindented ERROR line, that it carries `[<request id>]`, and that the query is absent from the whole output. Red before the fix: 3 ERROR-like lines, including Spring's `Error [...] for HTTP GET "/test-probe/committed?secret-query=..."` with an empty `[]` request ID.

## Revision (2026-11-03)

The fix was incomplete: on a real socket the raw query string still reaches the log after commit. The mapper returns `Mono.error(ex)` (`GatewayErrorWebExceptionHandler.kt:59`, `MockErrorWebExceptionHandler.kt:40`), and `ex` still carries the suppressed checkpoint. `HttpWebHandlerAdapter` is off, but Reactor Netty's `HttpServerOperations` then logs `ERROR ... Error finishing response. Closing connection` with that throwable, and Logback prints the `Suppressed:` section.

Found in the second-pass review with spring-web 7.0.10, reactor-netty 1.3.8 and reactor-core 3.8.8 on a real `HttpServer` (scratch `WebHttpHandlerBuilder` + `ReactorHttpHandlerAdapter`, a handler that flushes one chunk and then fails, a mapper that returns `Mono.error(ex)`), request `GET /x?secret-query=abc123`:

```
ERROR reactor.netty.http.server.HttpServerOperations -- [...] Error finishing response. Closing connection
java.lang.IllegalStateException: after commit
    Suppressed: The stacktrace has been enhanced by Reactor, refer to additional information below:
Error has been observed at the following site(s):
    *__checkpoint ⇢ HTTP GET "/x?secret-query=abc123" [ExceptionHandlingWebHandler]
```

`ErrorEnvelopeIntegrationTest:157` passes because it drives the `HttpHandler` bean with a mock exchange, so no Netty code runs. The smoke run's "no query string" on this line held only for `MockConnectionAbort`, which disables suppression; a bug in a mock stream or any gateway error after commit has the checkpoint. This is the check FND-04 handed to GW-02.

Fixed in the second-review fix commit (`Roadmap: FND-04`, `Roadmap: FND-03`). After logging, both mappers return a stackless stand-in instead of `ex`: `CommittedResponseAbort` (`GatewayErrorWebExceptionHandler.kt:50`, also `:91` for a write that fails after commit) and `MockConnectionAbort` (`MockErrorWebExceptionHandler.kt:55`). The connection still aborts and the Netty line has no query. It also gains the request ID, and it cures the normal-completion case in [bugs/error-mapper-disconnect-check-turns-errors-into-empty-200](error-mapper-disconnect-check-turns-errors-into-empty-200.md). Prevention, red before the fix (the captured log held `*__checkpoint ⇢ HTTP GET "/test-probe/committed-chunk?secret-query=abc123"`): `CommittedResponseIntegrationTest:29` and `MockCommittedResponseIntegrationTest:62` fail after commit on a `RANDOM_PORT` server through a raw socket, with a query in the URL, and grep the whole captured output. `GatewayErrorWebExceptionHandlerTest:79` replaces `assertSame(failure, thrown)`. Behaviour: [gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally](../gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md).
