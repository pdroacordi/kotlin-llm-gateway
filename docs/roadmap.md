# Roadmap

What is built, what is next, and what is waiting on a decision. **This is the starting point for choosing work.** `CLAUDE.md` says how to build; `docs/VISION.md` says what the product is; this file says where it stands.

## How to use it

- **Status:** `[x]` built · `[~]` in progress · `[ ]` not started · `[?]` waiting on a decision from the user · `[!]` blocked (reason in Notes) · `[-]` cut (reason in Notes).
- **Picking work:** continue any `[~]` first. Otherwise take the first `[ ]` whose `Needs` are all `[x]`, top-down (sections are in build order), or the order the user set. Never start a `[?]` item: ask the question in its Notes.
- **IDs are permanent.** A new item gets the next free number in its area; a cut item keeps its row as `[-]`. Commit bodies, specs and knowledge entries cite the ID (`Roadmap: RL-01`).
- **When an item changes status,** update its row and `Updated` date in the same commit as the code. When it is built, link its spec and its `docs/knowledge/systems/` entry in Notes.
- **Scope comes from `docs/VISION.md`.** An item outside it needs the user's go-ahead before it is added.

## Foundation

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| FND-01 | Gradle multi-module skeleton: Kotlin, Spring Boot, version catalog, ktlint, detekt, Kover, Konsist architecture tests, CI build job | `[x]` | | 2026-11-03 | Spec: [FND-01](specs/FND-01-gradle-skeleton.md). Built: modules `gateway-core` (no sources yet), `gateway-app` (actuator health on 8080), `architecture-tests` (Konsist ARCH-1 to ARCH-9, each with a violating fixture); Gradle 9.7.1, Kotlin 2.4.21, Spring Boot 4.1.2, JDK 25 toolchain; Kover, ktlint and detekt in `check`. No systems entry (the build is not a system). CI runs at the first push. |
| FND-02 | Local stack in `deploy/compose.yaml` (Postgres, Redis, Redpanda, OTel collector, Jaeger, Prometheus, Grafana) and Testcontainers base classes | `[x]` | FND-01 | 2026-11-03 | Ports from CLAUDE.md; `COMPOSE_PROJECT_NAME` and port overrides for worktrees. Spec: [FND-02](specs/FND-02-local-stack.md); cross-cutting [ADR-017](DECISIONS.md#adr-017); systems entry [local-stack](knowledge/systems/local-stack.md). Built: seven dev services in `deploy/compose.yaml` on `127.0.0.1` ports from `LLMGW_*_PORT` (Grafana on 3301, host enforced against DNS rebinding), OTLP through the collector to Jaeger and Prometheus, proven by `tools/smoke/stack-check.sh` (12 checks) locally and in a path-filtered `stack.yml`; module `test-support` with lazily started `SharedPostgres`, `SharedRedis`, `SharedRedpanda` and `SharedMockProvider`, images kept equal to compose by `StackContractTest`, integrationTest-only by ARCH-11 and `checkTestSupportClasspath`. Smoke [run](knowledge/runs/2026-11-03-smoke-local-stack.md) pass-with-findings, findings fixed. `stack.yml`'s first CI result waits for the first push. |
| FND-03 | `mock-provider`: OpenAI- and Anthropic-shaped fake with configurable latency, errors, streaming and usage; request counter and request log | `[x]` | FND-01 | 2026-11-03 | Every test and smoke run uses it; nothing in CI calls a real provider. Spec: [FND-03](specs/FND-03-mock-provider.md); cross-cutting [ADR-016](DECISIONS.md#adr-016); systems entry [mock-provider](knowledge/systems/mock-provider.md). Built: a Spring Boot WebFlux app on 8090 that imports nothing of ours, serving the OpenAI chat, embeddings and models paths and Anthropic messages with deterministic content and usage, provider-exact SSE flushed per event, per-model scenarios (latency, errors, faults) and a redacted request journal under `/__mock`; a Testcontainers image from its boot jar, tested in the new `integrationTest` suite. Smoke [run](knowledge/runs/2026-11-03-smoke-mock-provider-and-errors.md) pass-with-findings, finding fixed. |
| FND-04 | Request context and error model: request ID, OpenAI error envelope, one exception mapper | `[x]` | FND-01 | 2026-11-03 | Spec: [FND-04](specs/FND-04-request-context-and-errors.md); cross-cutting [ADR-015](DECISIONS.md#adr-015); systems entry [request-context-and-errors](knowledge/systems/request-context-and-errors.md). Built: gateway-generated UUIDv7 `x-request-id` on every response, validated and echoed `x-client-request-id`, every error as the OpenAI envelope from one `ErrorWebExceptionHandler` in `gateway.app.error` (ARCH-10). Smoke runs with the first `gateway-tester` run after FND-03; BlockHound runs in `gateway-app`'s `test` suite (2026-11-03): `RequestIdGenerator.next()` with the seeded DRBG passes on the event loop, so the `SplittableRandom` fallback is not needed. |
| FND-05 | Upgrade to stable Spring Boot 4.2 and detekt 2.0 when released | `[ ]` | FND-01 | 2026-11-03 | FND-01 pinned the pre-release detekt 2.0.0-alpha.6 and Spring Boot 4.1.2 (ADR-FND-01-001, ADR-FND-01-002 in the [FND-01 spec](specs/FND-01-gradle-skeleton.md)). Start only once both GA releases exist. |
| FND-06 | `mock-provider`'s `ShutdownIntegrationTest` stops closing a context on global Reactor resources | `[ ]` | FND-03 | 2026-11-04 | Same shape as the GW-04 [hang](knowledge/bugs/started-test-contexts-dispose-global-reactor-resources.md): closing a fully started context disposes the JVM's global `HttpResources` under every other context. `mock-provider` cannot use `gateway-app`'s `isolatedGateway()`, since it imports nothing of ours. |

## Gateway

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| GW-01 | `POST /v1/chat/completions` (non-streaming) through `ChatModelPort`; OpenAI adapter | `[x]` | FND-03, FND-04 | 2026-11-03 | Spec: [GW-01](specs/GW-01-chat-completions.md); cross-cutting [ADR-018](DECISIONS.md#adr-018); systems entry [provider-adapters](knowledge/systems/provider-adapters.md). Creates `systems/provider-adapters`. If routes are functional, translate a request-side DataBufferLimitException to ContentTooLarge ([bugs/gateway-body-limit-on-functional-route-gives-500](knowledge/bugs/gateway-body-limit-on-functional-route-gives-500.md)). Built: an OpenAI SDK with its base URL on the gateway gets a provider-shaped completion through `ChatModelPort`, with unknown members verbatim both ways and every upstream failure a sealed `ProviderFailure` and one error row. Smoke [run](knowledge/runs/2026-11-03-smoke-chat-completions.md) pass-with-findings, findings fixed or recorded; 63 PASS, 0 FAIL on the final jars. Live run passed: [report](knowledge/runs/2026-11-04-live-openai.md). |
| GW-02 | SSE streaming end to end; cancellation on client disconnect | `[x]` | GW-01 | 2026-11-04 | Spec: [GW-02](specs/GW-02-sse-streaming.md); cross-cutting [ADR-019](DECISIONS.md#adr-019); systems entry [provider-adapters](knowledge/systems/provider-adapters.md). BlockHound already guards `gateway-app` (`BlockHoundIntegrationTest`), so the streaming path runs under it from its first test; the check of `RequestIdGenerator.next()` is settled (FND-04 Log). Built: an OpenAI SDK reads `stream: true` through the gateway chunk by chunk, with the status committed at the first frame, an in-band error frame and a clean end after it, and a client disconnect cancelling the provider call. Smoke [run](knowledge/runs/2026-11-04-smoke-sse-streaming.md) `fail` on the first build, pass-with-findings on the fix build, findings fixed or recorded; R1 went to GW-08. Extension member named `llmgw` by the user (ADR-019); real provider framing proven by the paid live [run](knowledge/runs/2026-11-04-live-openai.md). |
| GW-03 | Anthropic adapter: messages, tool calls and stream translation | `[x]` | GW-02 | 2026-11-04 | Spec: [GW-03](specs/GW-03-anthropic-adapter.md); cross-cutting [ADR-020](DECISIONS.md#adr-020); systems entry [provider-adapters](knowledge/systems/provider-adapters.md). Built: an OpenAI SDK with a Claude model gets an OpenAI-shaped completion or stream, tool calls both ways, thinking replay through `llmgw`, normalised usage, and a 400 naming any member the adapter cannot honour, through `RoutingChatModelPort` and `.gateway.providers.upstream`; the mock gained scripted tool calls. Smoke [run](knowledge/runs/2026-11-04-smoke-anthropic-adapter.md) pass-with-findings, findings fixed or corrected in the spec. Not proven against the real API: L1 to L4 of spec section 8 (GW-11). |
| GW-04 | Teams and API keys: admin API, HMAC-hashed keys, auth filter | `[x]` | FND-02, GW-01 | 2026-11-04 | Spec: [GW-04](specs/GW-04-teams-and-api-keys.md); cross-cutting [ADR-021](DECISIONS.md#adr-021), [ADR-022](DECISIONS.md#adr-022); systems entry [auth-and-teams](knowledge/systems/auth-and-teams.md). Built: every `/v1` request resolves to one team through `AuthWebFilter`, operators manage teams and keys under `/admin/v1`, keys are HMAC digests in Postgres behind a Caffeine cache, and an outage fails closed; `gateway-infra` created, GW-01's stopgap deleted. Smoke [run](knowledge/runs/2026-11-04-smoke-teams-and-keys.md) pass-with-findings, findings fixed or corrected in the spec. Open: the plain-key criterion stays unticked, because `/actuator;k=<key>` echoes a URL parameter ([bug](knowledge/bugs/unknown-route-message-echoes-the-path-into-the-debug-log.md)). |
| GW-05 | `POST /v1/embeddings` | `[ ]` | GW-04 | 2026-11-03 | Needed by CACHE-02 and AGT-05. |
| GW-06 | Tool calling and `response_format` (JSON mode) pass-through | `[ ]` | GW-03 | 2026-11-04 | Proves tool calling end to end on the OpenAI path, on the scripted tool calls GW-03 added to `mock-provider`. Decides `response_format` on both adapters. Counts tool output in `UsageEstimator`. |
| GW-07 | Local models through an OpenAI-compatible server | `[?]` | GW-01 | 2026-11-03 | Which local runtime, if any? It would make evals free to run. |
| GW-08 | Demand-aware idle timeout: do not count time the client, not the provider, holds the stream | `[ ]` | GW-02 | 2026-11-04 | Smoke R1 ([run](knowledge/runs/2026-11-04-smoke-sse-streaming.md), [gotcha](knowledge/gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout.md)): `Flux.timeout` cannot see demand, so at the defaults a reader below about 11.8 to 19.7 KB/s on 16 KB frames gets `upstream_timeout` from a healthy provider. RTE-02 must not read it as a sick provider. |
| GW-09 | Non-text input on the Anthropic adapter: `image_url` parts to image blocks | `[ ]` | GW-03 | 2026-11-04 | Follow-up of [GW-03](specs/GW-03-anthropic-adapter.md) section 2 and ADR-GW-03-015: a non-text part is a 400 `unsupported_parameter` today, and the mock ignores images, so it must learn them first. |
| GW-10 | Relay Anthropic's `stop_details.category` under `llmgw` on a refusal | `[ ]` | GW-03 | 2026-11-04 | Follow-up of ADR-GW-03-010: a `refusal` gives `content_filter`, and the category is lost. Only if a client needs it. |
| GW-11 | Paid live check of the Anthropic adapter | `[?]` | GW-03 | 2026-11-04 | Does the user go ahead with a paid check against the real Anthropic API, and with which key? Settles L1 to L4 of [GW-03](specs/GW-03-anthropic-adapter.md) section 8: a tool result whose assistant turn omits thinking, thinking replay placement, the real 404 body, and framing, TLS and real `event: error` bodies. Local `.env` has `ANTHROPIC_KEY`, while the gateway reads `ANTHROPIC_API_KEY`, so the variable must be renamed or mapped first. |
| GW-12 | HMAC secret rotation: read two secret versions during a change of secret | `[ ]` | GW-04 | 2026-11-04 | Follow-up of ADR-GW-04-002: `team_keys.secret_version` is written, and reading two versions does not exist, so today a new secret means reissuing every key. Needs a dual lookup by version. |
| GW-13 | Keep a pasted key out of Reactor Netty's WARN line for a rejected request | `[ ]` | GW-04 | 2026-11-04 | Follow-up of [GW-04](specs/GW-04-teams-and-api-keys.md) Log: the line holds the path of a request Netty rejects (a control character, a header over 8 KB), read from smoke runs and not reproduced. Reproduce it with a canary first, then decide. |

## Observability

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| OBS-01 | OpenTelemetry tracing with the GenAI semantic conventions; a span per pipeline stage | `[ ]` | GW-01, FND-02 | 2026-11-03 | Creates `systems/observability`. |
| OBS-02 | Metrics (latency, TTFT, tokens/s, cache hit rate, errors per deployment) and a Grafana dashboard | `[ ]` | OBS-01 | 2026-11-03 | |
| OBS-03 | Structured logs with trace correlation; per-team opt-in content logging, redacted | `[ ]` | OBS-01, GRD-02 | 2026-11-03 | |

## Rate limiting

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| RL-01 | Per-team request and token limits in Redis (one atomic script per decision); 429 with limit headers | `[ ]` | GW-04, FND-02 | 2026-11-03 | Settles ADR-008. Creates `systems/rate-limiting`. |
| RL-02 | Token reservation before the call, reconciliation with real usage after (streams included) | `[ ]` | RL-01, GW-02 | 2026-11-03 | Reconciles from GW-02's `StreamSummary`, which fires once for cancelled streams too; a suspending call there needs `NonCancellable`. |
| RL-03 | Concurrency limits and fairness under load; behaviour when Redis is down | `[ ]` | RL-02, OPS-01 | 2026-11-03 | |

## Routing

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| RTE-01 | Model aliases and a versioned route table per team | `[ ]` | GW-04 | 2026-11-04 | Creates `systems/routing`. Replaces GW-03's `PrefixModelResolver`, and `model-prefixes` goes with it; owns `GET /v1/models`, which no row had (ADR-004, ADR-GW-01-006). |
| RTE-02 | Fallback chain, retries with jitter, per-deployment circuit breaker | `[ ]` | RTE-01, GW-02 | 2026-11-04 | Decides on GW-01's `ProviderFailure`. OpenAI requires `X-Client-Request-Id` unique per request, so each attempt needs its own value. A future fallback must not repeat a call the provider may have billed: `ProviderUnavailable` means nothing was sent, and every other transport failure is `UpstreamError`. On a stream, fallback is a loop inside `ChatCompletionService.stream`, on `Failed` before `Started` only (ADR-019). A circuit breaker must not count an `upstream_timeout` that a slow client caused: `Flux.timeout` cannot see demand, so a client too slow to pull a read within `idle-timeout` fails a healthy provider's stream (GW-02 section 5, Back-pressure). An in-band `ProviderUnavailable` means the provider took the request; `UnsupportedByProvider` means nothing was sent (GW-03). |
| RTE-03 | Weighted, latency-aware and cost-aware routing policies | `[ ]` | RTE-02, OBS-02, COST-01 | 2026-11-03 | `ai-eval` suite `routing`. |
| RTE-04 | Budget-aware downgrade to a cheaper model when a team nears its budget | `[ ]` | RTE-03, COST-03 | 2026-11-03 | |

## Caching

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| CACHE-01 | Exact cache: normalized request hash, team scope, TTL, bypass and status headers | `[ ]` | GW-04, FND-02 | 2026-11-03 | Creates `systems/caching`. |
| CACHE-02 | Semantic cache: embeddings, vector search, similarity threshold; scope by team, model and system prompt | `[ ]` | CACHE-01, GW-05 | 2026-11-03 | Settles ADR-009. |
| CACHE-03 | Semantic cache eval: labelled pairs, false-hit rate and hit rate against the threshold | `[ ]` | CACHE-02 | 2026-11-03 | `ai-eval` suite `semantic-cache`; builds the `evals/` runner. |
| CACHE-04 | Cached responses replayed as SSE streams | `[ ]` | CACHE-01, GW-02 | 2026-11-03 | A hit becomes a `Flow<ChatStreamEvent>`, so GW-02's `ChatStreamFrames` writes it unchanged. |

## Cost

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| COST-01 | Price catalog: input, output and cached-input prices per model, with effective dates | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/cost`. |
| COST-02 | Per-call cost, usage events, idempotent ledger writer in Postgres | `[ ]` | COST-01, FND-02 | 2026-11-03 | Settles ADR-010. Streams report through GW-02's `StreamSummary`, cancelled ones included, with `estimated` when the provider's usage never arrived; decides whether cost joins `llmgw` on the usage chunk. |
| COST-03 | Team budgets (soft and hard), alerts, cost query API by team, model and day | `[ ]` | COST-02 | 2026-11-03 | |

## Guardrails

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| GRD-01 | Guardrail pipeline: input and output stages, per-team policy, fail-open or fail-closed per rule | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/guardrails`. |
| GRD-02 | PII detection and redaction (CPF, CNPJ, e-mail, phone, card) on input and output | `[ ]` | GRD-01 | 2026-11-03 | `ai-eval` suite `pii`. |
| GRD-03 | Prompt-injection detection (classifier or judge model) with an eval set | `[ ]` | GRD-01 | 2026-11-03 | `ai-eval` suite `injection`. |
| GRD-04 | Output JSON-schema validation with one repair retry | `[ ]` | GRD-01, GW-06 | 2026-11-03 | |
| GRD-05 | Guardrails on streams: sliding-window scan and cut-off | `[ ]` | GRD-02, GW-02 | 2026-11-03 | A stage on `Flow<ChatStreamEvent>` that reads `ChunkDelta.content` and ends a stream with `Failed`, which GW-02 renders as an in-band error (ADR-019). |

## Agents

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| AGT-01 | Agent registry: versioned definitions (model alias, instructions, tools, limits); CRUD API | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/agent-runtime`. |
| AGT-02 | Tool registry and tool-calling loop: HTTP tools with JSON schema, host allowlist, step and time limits | `[ ]` | AGT-01, GW-06 | 2026-11-04 | SSRF review mandatory. Echoes `llmgw` on assistant messages in its tool loop (ADR-GW-03-011). |
| AGT-03 | Durable runs: run and step state in Postgres; resume after a crash without repeating steps | `[ ]` | AGT-02 | 2026-11-03 | |
| AGT-04 | Orchestration patterns as data: sequential, parallel fan-out and fan-in, supervisor | `[ ]` | AGT-03 | 2026-11-03 | `ai-eval` suite `agents`. |
| AGT-05 | Agent memory: conversation window and vector recall, scoped per agent and run | `[ ]` | AGT-03, GW-05 | 2026-11-03 | |
| AGT-06 | Human-in-the-loop approval step | `[ ]` | AGT-03 | 2026-11-03 | |
| AGT-07 | Tools from MCP servers | `[?]` | AGT-02 | 2026-11-03 | Client only, or also expose agents as an MCP server? |
| AGT-08 | Run observability: run trace linking gateway calls; per-run cost rollup | `[ ]` | AGT-03, OBS-01, COST-02 | 2026-11-03 | |

## CLI

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| CLI-01 | `llmgw` CLI: config and profiles, `models`, `chat` with streaming; request ID, cost and trace link per call | `[ ]` | GW-02, GW-04 | 2026-11-03 | "Yes, add a CLI" (user, 2026-11-03), to make the demo visible from a terminal. A pure HTTP client (ADR-012). |
| CLI-02 | Agent commands: register agents and tools from files, `run` with a live step stream, inspect runs (steps, cost, trace) | `[ ]` | CLI-01, AGT-03 | 2026-11-03 | Adds the run events stream (SSE) to agent-app. |
| CLI-03 | Admin commands: teams, keys, routes, prices, budgets; `usage` cost report per team | `[ ]` | CLI-01, RTE-01, COST-03 | 2026-11-03 | |

## Operations

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| OPS-01 | k6 load suite against the mock provider; measure the overhead budget | `[ ]` | GW-02, FND-03 | 2026-11-03 | Confirms or revises the VISION target (p99 overhead at most 15 ms). |
| OPS-02 | Multi-replica correctness: two gateway replicas, consistent limits and cache; Redis failover behaviour | `[ ]` | RL-02, CACHE-01 | 2026-11-03 | |
| OPS-03 | Container images and Kubernetes manifests (Helm), autoscaling on concurrency | `[ ]` | OPS-01 | 2026-11-03 | |
| OPS-04 | Public demo: one-command scenario, README walkthrough, architecture write-up | `[ ]` | AGT-04, COST-03, GRD-02, RTE-02, CLI-02 | 2026-11-03 | The "Done for v1" scenario in VISION, driven from the CLI. |
| OPS-05 | Admin web UI | `[?]` | | 2026-11-03 | Out of v1 per VISION; is a web UI worth it beyond Grafana, the admin API and the CLI? |
