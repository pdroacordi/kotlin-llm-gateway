---
id: bugs/unknown-route-message-echoes-the-path-into-the-debug-log
type: bug
title: The 404 message quoted the request path, so a key pasted into a URL reached the body and the encoder's DEBUG line
status: active
severity: low
tags: [errors, logging, secrets, auth, webflux]
related: [systems/request-context-and-errors, systems/auth-and-teams, gotchas/spring-jackson-encoder-logs-the-value-at-debug, runs/2026-11-04-smoke-teams-and-keys]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/ErrorReply.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslator.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/PathNoLeakTest.kt
---

## Summary

`unknown_url`'s message was `Unknown request URL: <METHOD> <path>.`. With the `web` log group at DEBUG, `JacksonJsonEncoder` logged that envelope, so a key in the path of an unknown route reached a log line. The 405 message never quoted the path.

## Symptom

Found by the [smoke run](../runs/2026-11-04-smoke-teams-and-keys.md), finding 1: `GET /nope/llmgw_<43 characters>` with `LOGGING_LEVEL_WEB=debug` gave 404 and `Encoding [OpenAiErrorEnvelope(error=Body(message=Unknown request URL: GET /nope/...`. `PathNoLeakTest` showed the same on three routes the run did not send a canary to: `/nope;key=<token>`, a keyed `/v1/<token>` and an admin-keyed `/admin/v1/<token>`.

## Root cause

FND-04 listed bodies, query strings and header values as request content that a message must not hold, and left the path out. The path passed through `toGatewayError(method, path)` into `UnknownRoute`.

## Fix

Fixed in GW-04 (Roadmap: GW-04). `GatewayError.UnknownRoute` is a `data object`, the message is `Unknown request URL.`, and `toGatewayError` takes no path, so the mapper has none to quote (ADR-GW-04-020). The (status, `type`, `code`) row is unchanged, so ADR-015's stability rule holds; that rule does not cover the message.

Red then green: `PathNoLeakTest`, 11 cases, 6 red on the body echo before the change, 11 green after. The other 5 (query string, 401, three 405s) already passed and stay as guards.

## What is left

Routes by which a path or query still reaches a body or a log, none changed here:

- **`/actuator;k=<token>`** is closed: `management.endpoints.web.discovery.enabled=false` makes `/actuator` a 404 (GW-04 Log), and `PathNoLeakTest` holds the case.
- **TRACE:** `RequestPredicates` logs `Pattern ... does not match against value "<path>"` for every route tried, whole. Not DEBUG, but `NoLeakIntegrationTest` uses TRACE as a first step of debugging.
- **WARN, the default level:** Reactor Netty's line for a request it rejects (a control character, a header over 8 KB) holds the path and filters the query ([gotcha](../gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux.md)). Read from the smoke runs, not reproduced with a canary here.
- **`mock-provider`:** `MockErrorTranslator` quotes the path in its 404 and 405. The gateway sends it only configured paths, never a client's.

Clean at DEBUG, by probe or by `PathNoLeakTest`: the query string on every route, 401s, 405s, `/actuator/health;k=<token>`, `/actuator/health/<token>`, `/actuator/<token>`. `HttpWebHandlerAdapter`, which logs path and query at DEBUG, is `off`.

## Prevention

Contract 6 of [request-context-and-errors](../systems/request-context-and-errors.md) now names the path. A type that holds no path cannot quote one.
