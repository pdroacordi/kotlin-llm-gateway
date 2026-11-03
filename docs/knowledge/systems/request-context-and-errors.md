---
id: systems/request-context-and-errors
type: system
title: Request context and errors: gateway-generated x-request-id, OpenAI error envelope, one mapper
status: active
tags: [errors, request-id, webflux, coroutines, logging, openai-compat]
related: [patterns/one-error-funnel, patterns/request-context-coroutine-element, patterns/architecture-rule-with-violating-fixture, bugs/error-mapper-disconnect-check-turns-errors-into-empty-200, bugs/arch-10-misses-co-web-exception-handler-and-problem-details-handler, bugs/committed-response-error-logged-twice-with-query-string, bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint, gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query, gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes, gotchas/spring-httpstatus-deprecated-aliases-never-resolved]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/request/RequestId.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/request/RequestIdGenerator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/request/ClientRequestId.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/ErrorReply.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextWebFilter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslator.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/LoggableFailure.kt
  - gateway-app/src/main/resources/application.yaml
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

Every response WebFlux handles carries a gateway-generated UUIDv7 in `x-request-id`, the header OpenAI returns and its SDKs read. Every error, from a filter, handler mapping, codec, handler or bug, reaches the client as the OpenAI envelope `{"error":{"message","type","param","code"}}`, written by one `WebExceptionHandler`.

## Contract (never break)

1. Every response WebFlux handles carries a gateway-generated `x-request-id`; every error body is the OpenAI envelope written only by `gateway.app.error`; a 5xx body never carries an exception message. Guarded by `RequestContextIntegrationTest`, `ErrorEnvelopeIntegrationTest` ("a bug gives 500 ...") and ARCH-10 (`ErrorHandlingRulesTest`).
2. A request's own `x-request-id` is ignored: the ID is the usage-event idempotency key (ADR-FND-04-001). `RequestContextIntegrationTest` "a request's own x-request-id is ignored".
3. `x-client-request-id` is 1 to 512 printable ASCII characters, sent once, and echoed; otherwise 400 `invalid_client_request_id`. An empty value counts as absent. `ClientRequestIdTest`, `RequestContextIntegrationTest`.
4. The envelope has exactly one member `error` with exactly four members, always present (`param` and `code` may be `null`), `Content-Type: application/json`, never `application/problem+json`, never Boot's `timestamp`/`path`/`status`. `ErrorEnvelopeIntegrationTest.assertEnvelope`.
5. A shipped (status, `type`, `code`) row never changes; a new condition gets a new code (ADR-015). The rows are FND-04 section 4 and `ErrorReplyTest`, one case per row.
6. Messages never contain request content (bodies, query strings, header values other than the two IDs) or exception messages; 4xx exception messages are never logged. `ErrorEnvelopeIntegrationTest` "malformed JSON gives 400 invalid_body without echoing the body".
7. The context holds exactly one `ErrorWebExceptionHandler`, `GatewayErrorWebExceptionHandler`; the `WebExceptionHandler` beans are exactly it and Spring's `WebFluxResponseStatusExceptionHandler`; no `@ControllerAdvice` bean exists, and no error-handling type is referenced outside `<app root>.error` (ARCH-10). `ErrorEnvelopeIntegrationTest` "the context holds exactly one ErrorWebExceptionHandler" and "the only WebExceptionHandlers are ...".
8. Before commit, every error gets the envelope: a disconnect check never skips the write. `GatewayErrorWebExceptionHandlerTest` "a decoding error quoting broken pipe still gets the 400 invalid_body envelope".
9. Query strings never reach the log, including 5xx stack traces and committed-response errors. `ErrorEnvelopeIntegrationTest` "a bug gives 500 ..." and "an error after commit gives one ERROR line ...".

## How it works

- **Request ID** (`gateway-core`): `RequestIdGenerator.next()` (`RequestIdGenerator.kt:15`) reads `clock.millis()` once, draws 10 bytes from the injected `Random`, sets version 7 and variant `0b10` (RFC 9562 section 5.7). A clock outside `0 until 2^48` ms fails `check` and becomes a 500.
- **Filter** (`RequestContextWebFilter.kt:26`, order `HIGHEST_PRECEDENCE`): generates the ID, sets `x-request-id`, stores `RequestContext` in the exchange attribute `RequestContext.ATTRIBUTE`, validates and echoes `x-client-request-id` (a bad one throws `GatewayErrorException`), then runs the chain in `withContext(RequestContext + MDCContext(...))` (`:42`). `suspend` handlers read `currentCoroutineContext()[RequestContext]` and MDC `requestId` across dispatcher hops, through `CoWebFilter`'s `COROUTINE_CONTEXT_ATTRIBUTE`.
- **Wiring** (`RequestContextConfiguration.kt:16`): `Clock.systemUTC()` and a DRBG `SecureRandom`, seeded by one draw in the bean method (`:20`), wrapped with `asKotlinRandom()`.
- **Errors in core**: the sealed `GatewayError` (`GatewayError.kt:7`) and the exhaustive table `GatewayError.toReply(requestId)` (`ErrorReply.kt:48`), giving status, `type`, `code`, message and headers (`allow` on 405).
- **Translator** (`ExceptionTranslator.kt:19`): `GatewayErrorException` gives its error; a `ServerWebInputException` with a `DataBufferLimitException` in its cause chain gives `ContentTooLarge` (`:30`), as does `ContentTooLargeException` through its 413; any `ErrorResponse` is matched on its status integer; everything else is `Internal`. A `DataBufferLimitException` outside the request-body read path is a 500: adapters must translate their own (for example an upstream response over the codec limit).
- **Mapper** (`GatewayErrorWebExceptionHandler.kt:41`, order −2, declared in `ErrorHandlingConfiguration`): before commit it always translates, calls `toReply`, sets status, `application/json`, reply headers and `x-request-id`, and writes the DTO through the configured codecs (`writeReply`, `:62`; `BodyInserters.fromValue` at `:74-76`). A write failure that `DisconnectedClientHelper` attributes to the client is logged at DEBUG and completes (`:78`); any other write failure gets the constant 500 body (`writeFallback`, `:88`). After commit: ERROR log and the error propagates. `DisconnectedClientHelper` on the original exception (`:48`) only lowers the log level, because it matches phrases anywhere in the cause chain. 5xx are logged at ERROR with the stack trace, 4xx at DEBUG with status and code only (`:114`), each inside `MDC.putCloseable("requestId", ...)`. The logged throwable is `LoggableFailure.of(ex)` (`LoggableFailure.kt:17`, called at the mapper's `:56`, `:95` and `:134`), a copy that keeps the full stack trace (taken back from the checkpoint, `:28-32`) without the Reactor checkpoint that Spring's `ExceptionHandlingWebHandler` attaches with the raw query string ([bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint](../bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint.md)).
- **Logs**: `logging.pattern.correlation: "[%X{requestId:-}] "` puts the ID in every console line. `HttpWebHandlerAdapter` is set to `off`: it only repeated committed-response errors without the ID and with the query string ([bugs/committed-response-error-logged-twice-with-query-string](../bugs/committed-response-error-logged-twice-with-query-string.md)).
- **Guard**: ARCH-10 `errorHandlingOnlyInErrorPackage` (`ArchitectureRules.kt:220`, types at `:66`), fixture `arch-10-error-handling-outside-error-package`. It matches names, not types: a typealias or subclass declared inside `.error` and used elsewhere is caught only by the runtime bean-set assertion (contract 7).

The error table (implemented and reserved rows) is FND-04 section 4: [`specs/FND-04-request-context-and-errors.md`](../../specs/FND-04-request-context-and-errors.md#4-contract).

## Failure behaviour

- Exception before the response is committed: envelope with the translated status. After commit (a stream): one ERROR log line with the request ID, the connection is closed, the client sees a truncated response. GW-02 must turn stream failures into an SSE error event carrying the same envelope before they reach the mapper, and confirms over a real stream that the committed path still logs one line with no query string (proven here with a mock exchange through the real `HttpHandler` bean).
- The envelope cannot be serialized: constant pre-serialized 500 envelope.
- Client disconnected: before commit the envelope is still written (a write to a closed connection fails harmlessly); a write failure attributed to the client is logged at DEBUG, and an exception that looks like a disconnect is logged at DEBUG instead of ERROR. After commit: DEBUG log, the error propagates ([bugs/error-mapper-disconnect-check-turns-errors-into-empty-200](../bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md)).
- Rejected by Reactor Netty before WebFlux (malformed request line, an initial line over 4 KB, a header over 8 KB): Netty's own response, no envelope, no `x-request-id`.
- No dependency: FND-04 calls no provider, Redis, Postgres or broker.

## Configuration

| Property | Value | Note |
|---|---|---|
| `spring.web.resources.add-mappings` | `false` | No static files; an unknown path is a plain 404. |
| `spring.webflux.problemdetails.enabled` | `false` (default, explicit) | `true` registers a second mapper. |
| `logging.pattern.correlation` | `"[%X{requestId:-}] "` | OBS-01 extends it with `traceId`/`spanId`. |
| `logging.level.org.springframework.web.server.adapter.HttpWebHandlerAdapter` | `off` | The mapper logs every error; this logger only duplicated them with the query string. |
| `spring.http.codecs.max-in-memory-size` | Spring default (256 KB per codec) | GW-01 sets the production body limit. |

Header names (`GatewayHeaders`) and the 512-character limit (`ClientRequestId.MAX_LENGTH`) are contract constants, not properties.

## Decisions

- [ADR-015](../../DECISIONS.md#adr-015): request ID and error contract.
- FND-04 ADR-FND-04-001 to -007: header and origin, UUIDv7 generator, coroutine element plus MDC, error model in core, one mapper, test-only probe controller, ARCH-10.

## Gotchas

- `SecureRandom.getInstance("DRBG")` does not seed; the first `nextBytes` reads entropy. The bean method draws once so request threads never do (JDK 25.0.4: 100 000 later 10-byte draws read no entropy). Every draw takes the shared instance's `synchronized` lock, so all request threads serialize on it; OPS-01 measures the contention and, if it shows, uses one DRBG per event-loop thread ([gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes](../gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes.md)).
- `HttpStatus.resolve(413)` always returns `CONTENT_TOO_LARGE` in Spring 7.0.10; the trap is a branch on the deprecated `PAYLOAD_TOO_LARGE` or `UNPROCESSABLE_ENTITY`, which never matches. The translator compares status integers ([gotchas/spring-httpstatus-deprecated-aliases-never-resolved](../gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md)).
- `DisconnectedClientHelper` matches phrases such as "broken pipe" in any cause's message; use it for log levels only ([gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain](../gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md)).
- Spring's `ExceptionHandlingWebHandler` attaches a Reactor checkpoint naming the method, path and raw query string, as a suppressed exception, to every error before the mapper sees it; log `LoggableFailure.of(ex)`, never `ex` ([gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query](../gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)).
- `MDCContext` captures the MDC map when constructed: add values with a nested `withContext(MDCContext(...))`, not `MDC.put` ([patterns/request-context-coroutine-element](../patterns/request-context-coroutine-element.md)).
- A `@ControllerAdvice` anywhere takes controller and handler-mapping errors first; ARCH-10 fails the build ([patterns/one-error-funnel](../patterns/one-error-funnel.md)).
- Functional `coRouter` handlers are not verified to receive the filter's coroutine context (GW-01 checks if it uses them).
- A control character (0x01, 0x7F) in any request header value is rejected by Reactor Netty with an empty `400` before WebFlux runs: no envelope, no `x-request-id`, and one client-triggerable WARN line ([gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux](../gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux.md)).
