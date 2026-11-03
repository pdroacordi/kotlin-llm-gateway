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
