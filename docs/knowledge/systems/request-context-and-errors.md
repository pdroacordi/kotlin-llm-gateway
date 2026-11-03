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
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/CommittedResponseAbort.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/EventLoopWarmup.kt
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
9. Query strings never reach the log, including 5xx stack traces and committed-response errors, on a real socket too: after commit the mapper returns a stackless `CommittedResponseAbort`, never the original exception. `ErrorEnvelopeIntegrationTest` "a bug gives 500 ...", `CommittedResponseIntegrationTest`.
10. A 5xx is logged at ERROR unless its own write failed with a client disconnect; `DisconnectedClientHelper` is asked only about that write failure. An error after commit is always logged at ERROR. `GatewayErrorWebExceptionHandlerTest` "a 5xx quoting a disconnect ...", "a 5xx whose write fails because the client is gone ...".
11. Nothing on a request's path blocks the event loop: `gateway-app`'s tests run under BlockHound, and `RequestIdGenerator.next()` with the seeded production DRBG passes on the event loop. `BlockHoundIntegrationTest`.

## How it works

- **Request ID** (`gateway-core`): `RequestIdGenerator.next()` (`RequestIdGenerator.kt:15`) reads `clock.millis()` once, draws 10 bytes from the injected `Random`, sets version 7 and variant `0b10` (RFC 9562 section 5.7). A clock outside `0 until 2^48` ms fails `check` and becomes a 500.
- **Filter** (`RequestContextWebFilter.kt:26`, order `HIGHEST_PRECEDENCE`): generates the ID, sets `x-request-id`, stores `RequestContext` in the exchange attribute `RequestContext.ATTRIBUTE`, validates and echoes `x-client-request-id` (a bad one throws `GatewayErrorException`), then runs the chain in `withContext(RequestContext + MDCContext(...))` (`:42`). `suspend` handlers read `currentCoroutineContext()[RequestContext]` and MDC `requestId` across dispatcher hops, through `CoWebFilter`'s `COROUTINE_CONTEXT_ATTRIBUTE`.
- **Wiring** (`RequestContextConfiguration.kt:16`): `Clock.systemUTC()` and a DRBG `SecureRandom`, seeded by one draw in the bean method (`:20`), wrapped with `asKotlinRandom()`. BlockHound passes `next()` on the event loop, so the `SplittableRandom` fallback is not used (ADR-FND-04-002, FND-04 Log).
- **Warm-up** (`EventLoopWarmup.kt:18`, before the server accepts requests): initialises `ReactiveFlowKt` (`:19`) and resolves `kotlinFunction` and its parameter types for every Kotlin handler (`:28`), so neither a jar read nor kotlin-reflect's locks run on an event loop (ADR-FND-04-008).
- **Errors in core**: the sealed `GatewayError` (`GatewayError.kt:7`) and the exhaustive table `GatewayError.toReply(requestId)` (`ErrorReply.kt:48`), giving status, `type`, `code`, message and headers (`allow` on 405).
- **Translator** (`ExceptionTranslator.kt:19`): `GatewayErrorException` gives its error; a `ServerWebInputException` with a `DataBufferLimitException` in its cause chain gives `ContentTooLarge` (`:30`), as does `ContentTooLargeException` through its 413; any `ErrorResponse` is matched on its status integer; everything else is `Internal`. A `DataBufferLimitException` outside the request-body read path is a 500: adapters must translate their own (for example an upstream response over the codec limit).
- **Mapper** (`GatewayErrorWebExceptionHandler.kt:41`, order −2, declared in `ErrorHandlingConfiguration`): before commit it always translates, calls `toReply`, sets status, `application/json`, reply headers and `x-request-id`, and writes the DTO through the configured codecs (`writeReply`, `:53`; `BodyInserters.fromValue` at `:63-65`). The reply is logged after the write (`:66`): 5xx at ERROR with the stack trace, 4xx at DEBUG with status and code only (`logReply`, `:108`), each inside `MDC.putCloseable("requestId", ...)`. A write failure that `DisconnectedClientHelper` attributes to the client is logged at DEBUG and completes (`:71`), the only DEBUG path for a 5xx; any other write failure logs the reply and gets the constant 500 body (`writeFallback`, `:82`). The helper is never asked about the original exception, because it matches phrases anywhere in the cause chain. After commit: ERROR log, then `Mono.error(CommittedResponseAbort(requestId))` (`:49-50`, and `:91` for a write that fails after commit). The stand-in is stackless and takes no suppressed exception, so no checkpoint reaches Reactor Netty's log line, and it holds no phrase, so Spring does not end the response normally ([gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally](../gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md)). The logged throwable is `LoggableFailure.of(ex)` (`LoggableFailure.kt:20`, called at the mapper's `:49`, `:89` and `:116`), a copy that keeps the full stack trace (taken back from the checkpoint, `:34-38`), stops at a cycle through cause and suppressed (identity `seen` set), and leaves out the Reactor checkpoint that Spring's `ExceptionHandlingWebHandler` attaches with the raw query string ([bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint](../bugs/mapper-error-log-carries-raw-query-via-reactor-checkpoint.md)).
- **Logs**: `logging.pattern.correlation: "[%X{requestId:-}] "` puts the ID in every console line. `HttpWebHandlerAdapter` is set to `off`: it only repeated committed-response errors without the ID and with the query string ([bugs/committed-response-error-logged-twice-with-query-string](../bugs/committed-response-error-logged-twice-with-query-string.md)).
- **Guard**: ARCH-10 `errorHandlingOnlyInErrorPackage` (`ArchitectureRules.kt:220`, types at `:66`), fixture `arch-10-error-handling-outside-error-package`. It matches names, not types: a typealias or subclass declared inside `.error` and used elsewhere is caught only by the runtime bean-set assertion (contract 7).

The error table (implemented and reserved rows) is FND-04 section 4: [`specs/FND-04-request-context-and-errors.md`](../../specs/FND-04-request-context-and-errors.md#4-contract).

## Failure behaviour

- Exception before the response is committed: envelope with the translated status. After commit (a stream): the mapper's ERROR line with the request ID, Reactor Netty's `Error finishing response. Closing connection` with the stand-in, whose message names the request ID, and the connection is closed, so the client sees a truncated response without the terminating chunk (`CommittedResponseIntegrationTest`, over a real socket). GW-02 must turn stream failures into an SSE error event carrying the same envelope before they reach the mapper.
- The envelope cannot be serialized: constant pre-serialized 500 envelope.
- Client disconnected: before commit the envelope is still written; a write failure the helper attributes to the client is logged at DEBUG, and that is the only case where a 5xx has no ERROR line. An exception that only quotes a disconnect gets the envelope and ERROR, because a successful write proves the client was there. If the request is cancelled during the write, neither path runs and the 5xx is not logged. After commit there is no write to confirm a disconnect, so a real one also logs ERROR; the client's cancel normally stops the chain before the mapper ([bugs/error-mapper-disconnect-check-turns-errors-into-empty-200](../bugs/error-mapper-disconnect-check-turns-errors-into-empty-200.md)).
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

- `SecureRandom.getInstance("DRBG")` does not seed; the first `nextBytes` reads entropy. The bean method draws once so request threads never do. Under BlockHound on Linux the first draw trips (`FileInputStream#readBytes`), and after the startup draw 100 000 draws and a `reseed()` pass; on Windows nothing trips, so only a Linux run guards that draw (proven: without it `BlockHoundIntegrationTest` fails there). Every draw takes the shared instance's `synchronized` lock, so all request threads serialize on it; OPS-01 measures the contention and, if it shows, uses one DRBG per event-loop thread ([gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes](../gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes.md)).
- `HttpStatus.resolve(413)` always returns `CONTENT_TOO_LARGE` in Spring 7.0.10; the trap is a branch on the deprecated `PAYLOAD_TOO_LARGE` or `UNPROCESSABLE_ENTITY`, which never matches. The translator compares status integers ([gotchas/spring-httpstatus-deprecated-aliases-never-resolved](../gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md)).
- `DisconnectedClientHelper` matches phrases such as "broken pipe" in any cause's message; use it for log levels only ([gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain](../gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md)).
- Spring's `ExceptionHandlingWebHandler` attaches a Reactor checkpoint naming the method, path and raw query string, as a suppressed exception, to every error before the mapper sees it; log `LoggableFailure.of(ex)`, never `ex` ([gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query](../gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)).
- `MDCContext` captures the MDC map when constructed: add values with a nested `withContext(MDCContext(...))`, not `MDC.put` ([patterns/request-context-coroutine-element](../patterns/request-context-coroutine-element.md)).
- A `@ControllerAdvice` anywhere takes controller and handler-mapping errors first; ARCH-10 fails the build ([patterns/one-error-funnel](../patterns/one-error-funnel.md)).
- Functional `coRouter` handlers are not verified to receive the filter's coroutine context (GW-01 checks if it uses them).
- If routes are functional, translate a request-side `DataBufferLimitException` to `ContentTooLarge`: only the annotated-controller path gets Spring's translation, so GW-01 would otherwise answer 500 ([bugs/gateway-body-limit-on-functional-route-gives-500](../bugs/gateway-body-limit-on-functional-route-gives-500.md)).
- The first call of a Kotlin annotated handler runs kotlin-reflect on the event loop unless `EventLoopWarmup` resolved it at startup ([gotchas/kotlin-reflect-first-use-in-annotated-handlers-blocks-the-event-loop](../gotchas/kotlin-reflect-first-use-in-annotated-handlers-blocks-the-event-loop.md)).
- A control character (0x01, 0x7F) in any request header value is rejected by Reactor Netty with an empty `400` before WebFlux runs: no envelope, no `x-request-id`, and one client-triggerable WARN line ([gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux](../gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux.md)).
