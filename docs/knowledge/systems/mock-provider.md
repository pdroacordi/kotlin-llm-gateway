---
id: systems/mock-provider
type: system
title: Mock provider: a deterministic OpenAI- and Anthropic-shaped fake on :8090, driven by scenarios, with a request journal
status: active
tags: [mock-provider, testing, sse, openai-compat, anthropic, journal, blockhound]
related: [runs/2026-11-03-smoke-mock-provider-and-errors, patterns/raw-sse-frames-flushed-per-event, patterns/one-error-funnel, gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay, gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame, bugs/mock-serving-after-delay-runs-on-default-executor, bugs/mock-scenario-times-expanded-into-memory, bugs/mock-scenario-error-headers-not-validated, gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query, bugs/committed-response-error-logged-twice-with-query-string]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/build.gradle.kts
  - mock-provider/Dockerfile
  - mock-provider/src/main/resources/application.yaml
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/MockConfiguration.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/ServerConfiguration.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStore.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/CompletionPlanner.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/RequestJournal.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/ArrivalWebFilter.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/RequestRecord.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/SseBodyInserter.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorWebExceptionHandler.kt
---

## Summary

`mock-provider` is the upstream every test and smoke run uses instead of a real provider. It answers the OpenAI paths (`POST /v1/chat/completions`, `POST /v1/embeddings`, `GET /v1/models`) and the Anthropic path (`POST /v1/messages`) on port 8090. Content and usage are deterministic, and streams are framed byte for byte as the providers document them. Behaviour (latency, HTTP errors, faults, content, usage) comes from per-model scenarios set under `/__mock`. Every request is counted and kept, redacted, in a bounded journal. The same boot jar runs as a process (`bootRun`, `java -jar`) and as a Testcontainers image. Contract details: [FND-03 spec](../../specs/FND-03-mock-provider.md), section 4.

## Contract (never break)

1. The same request (model and conversation) always gets the same content and the same usage; the stream and non-stream forms agree. `TextGeneratorTest`, `CompletionPlannerTest`, `OpenAiStreamIntegrationTest` "stream and non-stream agree", `AnthropicMessagesIntegrationTest`.
2. A credential value is never stored, logged or returned: only the scheme and an 8-hex SHA-256 fingerprint. `HeaderRedactionTest`, `JournalIntegrationTest` (the key is absent from the responses, the admin responses and the captured log).
3. A scenario step is consumed by exactly one request, in the order the requests resolve their scenario (after the body is read and checked, so not strictly in arrival order). `ScenarioStoreTest` "under concurrency exactly times requests get a step" (1000 coroutines, `times: 37`), `FaultInjectionIntegrationTest` "two of ten concurrent requests get the 429 step".
4. Every non-admin request advances `lastSeq` and produces exactly one journal entry, misrouted ones included; after `reset` nothing from before it appears. `RequestJournalTest`, `JournalIntegrationTest` (unknown path; in-flight request across a reset).
5. Streams are written one flushed event at a time in the providers' framing. Golden files in `src/test/resources/golden` (`OpenAiRendererTest`, `AnthropicRendererTest`, the stream integration tests); `LatencyIntegrationTest` (pieces spread over at least 600 ms at `perTokenMs: 200`; measured 200 to 202 ms apart).
6. The mock imports nothing of ours: role `app`, empty `dependsOn` (ARCH-9, `Modules.kt`).
7. A read of `/__mock/stats` or `/__mock/requests` after a response finds that request's entry: both wait until `inFlight` is 0, for at most `mock.journal.read-wait`, then answer with what is there. A held request therefore delays a read by that bound. `JournalReadWaitIntegrationTest`.

Rule for callers: tests that share one mock isolate by model name and never reset it; a test that resets owns its mock instance (ADR-FND-03-004).

## How it works

- **Packages.** Pure (no Spring, Reactor or Jackson; plain JUnit tests): `behaviour`, `generation`, `journal`, `provider`, `sse`. This is a convention: no Konsist rule checks it (accepted at the FND-03 closing), so keep framework imports out of those packages by review. HTTP and JSON (Jackson 3 tree model only): `web`, `openai`, `anthropic`, `admin`. Wiring in `config`; the one exception mapper in `error` (ARCH-10).
- **Arrival** (`ArrivalWebFilter.kt:37`, `HIGHEST_PRECEDENCE`): every path outside `/__mock` and `/actuator` gets `journal.arrive()` (sequence, epoch, `inFlight`), a route id by path (`routeOf`, `:131`), redacted headers, the credential fingerprint and the provider request ID header (`x-request-id`, or `request-id` under `/v1/messages`) before anything can fail. The `RequestRecord` (`RequestRecord.kt`) goes into an exchange attribute.
- **One entry per request, whatever ends first.** The filter always takes part in the record; a streaming body joins it when it starts (`RequestRecord.join`, `:108`). The last to leave (`:116`) writes the entry. A client disconnect cancels both the filter's coroutine and the stream's, in no fixed order, and the outcome is still recorded once. Cancellation is recorded in `finally` blocks by `!currentCoroutineContext().isActive`, never by catching it.
- **Resolution** (`ScenarioStore.resolve`, `ScenarioStore.kt:30`): the model's scenario, else `*`; its next step through one atomic update of a `Long` cursor capped at the sum of `times` (`:112`), else `otherwise`. Steps are never expanded by `times`: a binary search over their cumulative ends finds the step. Unset fields inherit from the scenario's `otherwise`, the `*` scenario's `otherwise`, then `mock.defaults.*`. `error` and `fault` are inherited together, from the most specific level that sets either. `/v1/models` takes no scenario.
- **Generation** (`CompletionPlanner.plan`, `CompletionPlanner.kt:52`): content `lorem` (64-word vocabulary, seeded by FNV-1a 64 of model and turns), `echo` (the last user turn) or `fixed`. It is cut to the request's cap in pieces, one token each (`Tokenizer`: letter/digit runs and single symbols). `completion_tokens` equals the piece count. Prompt tokens are 3 per turn plus its tokens, plus 3. Anthropic's input is split so that input, cache-read and cache-creation sum to the OpenAI prompt count. Embeddings are feature hashing, L2-normalised; not semantic.
- **Serving** (`Serving.respond`, `Serving.kt:35`): wait `ttft + jitter`, then a held request (`hang`, or non-stream `stall`), or an injected error in the route's shape with `retry-after` / `retry-after-ms` and the step's headers, or the reply. Non-stream waits `perTokenMs` x pieces more. A stream sends the opening, then one piece per `perTokenMs`, then the closing events or the fault (`ending`, `:138`).
- **Waits** (`awaitDelay`, `Serving.kt:253`): every wait is a Reactor timer (`Mono.delay`), so the code after it runs on a `parallel` thread that BlockHound watches. `kotlinx.coroutines.delay` would continue on a thread BlockHound does not watch, because Spring starts coroutine handlers `Unconfined` ([gotcha](../gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)). `ServingThreadIntegrationTest`, `ReactorDelayTest`.
- **SSE** (`SseBodyInserter.kt:17`): frames are built as strings in pure code (`SseFrames`) and written through `writeAndFlushWith`, one inner publisher per event ([patterns/raw-sse-frames-flushed-per-event](../patterns/raw-sse-frames-flushed-per-event.md)).
- **Faults after commit**: `disconnect` and a `stall` at `mock.max-hold` raise `MockConnectionAbort` from the body publisher. The mapper sees a committed response, logs DEBUG by type (Reactor Netty still logs one ERROR line, see Failure behaviour) and returns the error (`abortAfterCommit`, `MockErrorWebExceptionHandler.kt:55`). Any other failure after commit is logged at ERROR and replaced by a stackless `MockConnectionAbort` naming the request, so its checkpoint and raw query never reach Reactor Netty's log (`MockCommittedResponseIntegrationTest`). Reactor Netty then closes the connection without the terminating chunk: WebClient reports `PrematureCloseException`, and a raw socket reads `200`, part of the body, then EOF.
- **Errors** (`MockErrorWebExceptionHandler.kt:26`): framework failures, `MockRejection` (validation, credentials, admin input) and bugs become the route's provider shape, Anthropic under `/v1/messages/**`, OpenAI elsewhere and on `/__mock`. 5xx bodies never carry an exception message.
- **Journal** (`RequestJournal.kt`): one `AtomicReference` state per epoch holding a lock-free ring (`AtomicReferenceArray` plus a write counter) and `LongAdder` counts. `arrive` reads the epoch before taking the sequence (`:40-41`); `complete` reads the state once and drops an entry from an older epoch (`:50-51`). `reset` swaps the state (`:77-78`); the sequence never rewinds. An entry is written after its response reaches the client, so the two read endpoints wait for `inFlight` 0 (`awaitJournalSettled`, `AdminHandlers.kt:105`; [bug](../bugs/mock-journal-entry-visible-after-the-response.md)).
- **Container**: `mock-provider/Dockerfile` (`eclipse-temurin:25-jre-noble` pinned by digest, non-root) plus the boot jar `mock-provider.jar`, exposed to other projects as the consumable configuration `mockProviderBootJar` (category attribute `mock-provider-boot-jar`). `MockProviderImageTest` builds it with `ImageFromDockerfile` in the `integrationTest` suite (ADR-016).

## Failure behaviour

- The client disconnects: the handler's `delay` or the stream is cancelled, and the entry says `client_cancelled` with `piecesSent` (`FaultInjectionIntegrationTest`).
- `hang` / `stall` longer than the client waits: held until `mock.max-hold`, then 504 (`timed_out`) or an abort.
- `reset` during in-flight requests: their completions are dropped; their arrivals already advanced `lastSeq`.
- Journal full: the oldest entries are overwritten and `journal.dropped` counts them.
- A `disconnect` abort (and a `stall` reaching `mock.max-hold`, same path) logs one ERROR line from Reactor Netty's `HttpServerOperations`: `Error finishing response. Closing connection` followed by `MockConnectionAbort`. It has no request ID and no query string. That is expected for this fault: the mapper's own line is DEBUG, and `HttpWebHandlerAdapter` is off, but this logger sits below WebFlux. Leave it on, because silencing it would also hide real Netty errors. A test that asserts "no ERROR" over a real socket must exclude this line ([runs/2026-11-03-smoke-mock-provider-and-errors](../runs/2026-11-03-smoke-mock-provider-and-errors.md), finding 1).
- Body over `spring.http.codecs.max-in-memory-size`: 413 in the provider shape.
- A bug: 500 in the provider shape, one ERROR line with the full stack trace, no query string. After commit: the same line, Reactor Netty's line with the stackless stand-in, and an aborted body.
- Stop signal: `server.shutdown: immediate`, `spring.reactor.netty.shutdown-quiet-period: 0s` and a 3 s Netty lifecycle timeout (`ServerConfiguration`; it also bounds the startup bind). With a held request the context closes after that 3 s, under 4 s in all (`ShutdownIntegrationTest`).
- No dependency of its own: no provider, Redis, Postgres or broker. State is process memory, lost on restart.

## Configuration

`mock-provider/src/main/resources/application.yaml`, bound to `MockProperties` and checked at startup; every key settable from the environment (`MOCK_DEFAULTS_LATENCY_TTFT=50ms`).

| Property | Default | Note |
|---|---|---|
| `server.port` | 8090 | CLAUDE.md local ports |
| `server.shutdown` | `immediate` | Graceful would wait out held requests. |
| `spring.reactor.netty.shutdown-quiet-period` | `0s` | The 2 s default delays every stop. |
| `spring.http.codecs.max-in-memory-size` | `10MB` | Request body limit |
| `mock.models` | `mock-chat`, `mock-chat-mini`, `mock-claude`, `mock-embed` | Listed by `/v1/models` only; any model is accepted. |
| `mock.defaults.latency.ttft` / `per-token` / `jitter` | `0ms` | Each 0..`max-hold` |
| `mock.defaults.completion-tokens` | 16 | 1..4096 |
| `mock.defaults.content-mode` | `lorem` | `lorem` or `echo` |
| `mock.embeddings.default-dimensions` / `max-dimensions` | 1536 / 3072 | |
| `mock.auth.require-credential` | `true` | `false` accepts requests with no key. |
| `mock.journal.capacity` | 1000 | 1..100000 entries |
| `mock.journal.max-body-bytes` | 16384 | Cut at a UTF-8 boundary; `bodyTruncated` |
| `mock.journal.capture-bodies` | `true` | `false` for load runs |
| `mock.journal.read-wait` | `250ms` | Longest wait of `/__mock/stats` and `/__mock/requests` for requests in flight; `0ms` answers at once |
| `mock.max-hold` | `300s` | Longest hold; also caps scenario latencies |

Limits that are constants, not properties: a scenario has at most 1000 steps and each `times` is at most 1 000 000 (`ScenarioJson`); `error.headers` names must be RFC 9110 tokens and values must pass Netty's own check (first character above 0x20 and not 0x7F; later characters not below 0x20, tab excepted, and not 0x7F); an embeddings request has at most 2048 inputs (OpenAI's limit). Each is a 400 naming `param`. Credentials are checked before the body is read, so no key and a bad body is 401.

## Decisions

- FND-03 ADR-FND-03-001 to -012 ([spec](../../specs/FND-03-mock-provider.md), section 10): a Spring Boot WebFlux app with role `app` and no dependency on our modules; reuse through a Testcontainers image; per-model scenarios; hash-seeded content and approximate usage; feature-hashed embeddings; raw SSE frames flushed per event; a lock-free journal with a 32-bit credential fingerprint; BlockHound in the mock's tests (since 2026-11-03 from `llmgw.spring-app`); Jackson tree model; tool calls left to GW-03/GW-06; the `integrationTest` suite (convention `llmgw.integration-test`, every module).
- [ADR-014](../../DECISIONS.md#adr-014) revision (role `app`), [ADR-016](../../DECISIONS.md#adr-016) (Docker tests in `integrationTest`).

## Gotchas

- `kotlinx.coroutines.reactive.ReactiveFlowKt`'s static initializer runs a `ServiceLoader` (a jar read). The first `CoWebFilter` request triggers it on an event-loop thread, and BlockHound fails it. `MockConfiguration.arrivalWebFilter` initialises the class at startup (`MockConfiguration.kt:75`). `gateway-app` does the same in `EventLoopWarmup`.
- A Reactor checkpoint (Spring adds one per filter and in `ExceptionHandlingWebHandler`) cuts the failing exception's own stack trace to its first frame and moves the full trace into the suppressed `OnAssemblyException`. The mock's `LoggableFailure`, identical to gateway-app's, takes the frames back from it, so a logged bug shows the whole trace without the query text ([gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame](../gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame.md), [gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query](../gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query.md)).
- `DisconnectedClientHelper` matches phrases anywhere in a cause chain; the mapper uses it only to pick a log level, and decides intentional aborts by the `MockConnectionAbort` type ([gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain](../gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md)).
- `HttpWebHandlerAdapter` is `off`: every intentional abort would otherwise log an ERROR line with the raw query string ([bugs/committed-response-error-logged-twice-with-query-string](../bugs/committed-response-error-logged-twice-with-query-string.md)).
- ARCH-10 also covers test sources: a test that names `WebExceptionHandler` or `ErrorWebExceptionHandler` lives in `.mockprovider.error` (`MockErrorEnvelopeIntegrationTest`).
- `@DirtiesContext(BEFORE_METHOD)` re-injects fields only. The golden integration tests, which need arrival sequence 1, use field injection.
- Tests must not assert `lorem` words; golden files use `fixed` content.
- A value from an admin or request body that sizes an allocation needs an upper bound ([bugs/mock-scenario-times-expanded-into-memory](../bugs/mock-scenario-times-expanded-into-memory.md)); header names and values from a scenario are validated at `PUT`, not when Netty writes them ([bugs/mock-scenario-error-headers-not-validated](../bugs/mock-scenario-error-headers-not-validated.md)).
- `complete` decrements `inFlight` last, so a caller that sees it drop finds the entry. The journal reads the epoch before taking the sequence, so an arrival racing a reset is dropped with the old epoch; a ring slot keeps the higher write number when two writers a capacity apart race; the debug log escapes control characters in the model name.
- Each intentional abort writes one ERROR line from `reactor.netty.http.server.HttpServerOperations` without request ID or query (see Failure behaviour); expected, and that logger stays on.
