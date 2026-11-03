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
| FND-01 | Gradle multi-module skeleton: Kotlin, Spring Boot, version catalog, ktlint, detekt, Kover, Konsist architecture tests, CI build job | `[ ]` | | 2026-11-03 | Pins the then-current stable Kotlin, Spring Boot and LTS JDK; updates the module list and Testing commands in CLAUDE.md. |
| FND-02 | Local stack in `deploy/compose.yaml` (Postgres, Redis, Redpanda, OTel collector, Jaeger, Prometheus, Grafana) and Testcontainers base classes | `[ ]` | FND-01 | 2026-11-03 | Ports from CLAUDE.md; `COMPOSE_PROJECT_NAME` and port overrides for worktrees. |
| FND-03 | `mock-provider`: OpenAI- and Anthropic-shaped fake with configurable latency, errors, streaming and usage; request counter and request log | `[ ]` | FND-01 | 2026-11-03 | Every test and smoke run uses it; nothing in CI calls a real provider. |
| FND-04 | Request context and error model: request ID, OpenAI error envelope, one exception mapper | `[ ]` | FND-01 | 2026-11-03 | |

## Gateway

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| GW-01 | `POST /v1/chat/completions` (non-streaming) through `ChatModelPort`; OpenAI adapter | `[ ]` | FND-03, FND-04 | 2026-11-03 | Creates `systems/provider-adapters`. |
| GW-02 | SSE streaming end to end; cancellation on client disconnect | `[ ]` | GW-01 | 2026-11-03 | |
| GW-03 | Anthropic adapter: messages, tool calls and stream translation | `[ ]` | GW-02 | 2026-11-03 | |
| GW-04 | Teams and API keys: admin API, HMAC-hashed keys, auth filter | `[ ]` | FND-02, GW-01 | 2026-11-03 | Creates `systems/auth-and-teams`; decides R2DBC vs JDBC (ADR-006). |
| GW-05 | `POST /v1/embeddings` | `[ ]` | GW-04 | 2026-11-03 | Needed by CACHE-02 and AGT-05. |
| GW-06 | Tool calling and `response_format` (JSON mode) pass-through | `[ ]` | GW-03 | 2026-11-03 | |
| GW-07 | Local models through an OpenAI-compatible server | `[?]` | GW-01 | 2026-11-03 | Which local runtime, if any? It would make evals free to run. |

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
| RL-02 | Token reservation before the call, reconciliation with real usage after (streams included) | `[ ]` | RL-01, GW-02 | 2026-11-03 | |
| RL-03 | Concurrency limits and fairness under load; behaviour when Redis is down | `[ ]` | RL-02, OPS-01 | 2026-11-03 | |

## Routing

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| RTE-01 | Model aliases and a versioned route table per team | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/routing`. |
| RTE-02 | Fallback chain, retries with jitter, per-deployment circuit breaker | `[ ]` | RTE-01, GW-02 | 2026-11-03 | |
| RTE-03 | Weighted, latency-aware and cost-aware routing policies | `[ ]` | RTE-02, OBS-02, COST-01 | 2026-11-03 | `ai-eval` suite `routing`. |
| RTE-04 | Budget-aware downgrade to a cheaper model when a team nears its budget | `[ ]` | RTE-03, COST-03 | 2026-11-03 | |

## Caching

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| CACHE-01 | Exact cache: normalized request hash, team scope, TTL, bypass and status headers | `[ ]` | GW-04, FND-02 | 2026-11-03 | Creates `systems/caching`. |
| CACHE-02 | Semantic cache: embeddings, vector search, similarity threshold; scope by team, model and system prompt | `[ ]` | CACHE-01, GW-05 | 2026-11-03 | Settles ADR-009. |
| CACHE-03 | Semantic cache eval: labelled pairs, false-hit rate and hit rate against the threshold | `[ ]` | CACHE-02 | 2026-11-03 | `ai-eval` suite `semantic-cache`; builds the `evals/` runner. |
| CACHE-04 | Cached responses replayed as SSE streams | `[ ]` | CACHE-01, GW-02 | 2026-11-03 | |

## Cost

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| COST-01 | Price catalog: input, output and cached-input prices per model, with effective dates | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/cost`. |
| COST-02 | Per-call cost, usage events, idempotent ledger writer in Postgres | `[ ]` | COST-01, FND-02 | 2026-11-03 | Settles ADR-010. |
| COST-03 | Team budgets (soft and hard), alerts, cost query API by team, model and day | `[ ]` | COST-02 | 2026-11-03 | |

## Guardrails

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| GRD-01 | Guardrail pipeline: input and output stages, per-team policy, fail-open or fail-closed per rule | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/guardrails`. |
| GRD-02 | PII detection and redaction (CPF, CNPJ, e-mail, phone, card) on input and output | `[ ]` | GRD-01 | 2026-11-03 | `ai-eval` suite `pii`. |
| GRD-03 | Prompt-injection detection (classifier or judge model) with an eval set | `[ ]` | GRD-01 | 2026-11-03 | `ai-eval` suite `injection`. |
| GRD-04 | Output JSON-schema validation with one repair retry | `[ ]` | GRD-01, GW-06 | 2026-11-03 | |
| GRD-05 | Guardrails on streams: sliding-window scan and cut-off | `[ ]` | GRD-02, GW-02 | 2026-11-03 | |

## Agents

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| AGT-01 | Agent registry: versioned definitions (model alias, instructions, tools, limits); CRUD API | `[ ]` | GW-04 | 2026-11-03 | Creates `systems/agent-runtime`. |
| AGT-02 | Tool registry and tool-calling loop: HTTP tools with JSON schema, host allowlist, step and time limits | `[ ]` | AGT-01, GW-06 | 2026-11-03 | SSRF review mandatory. |
| AGT-03 | Durable runs: run and step state in Postgres; resume after a crash without repeating steps | `[ ]` | AGT-02 | 2026-11-03 | |
| AGT-04 | Orchestration patterns as data: sequential, parallel fan-out and fan-in, supervisor | `[ ]` | AGT-03 | 2026-11-03 | `ai-eval` suite `agents`. |
| AGT-05 | Agent memory: conversation window and vector recall, scoped per agent and run | `[ ]` | AGT-03, GW-05 | 2026-11-03 | |
| AGT-06 | Human-in-the-loop approval step | `[ ]` | AGT-03 | 2026-11-03 | |
| AGT-07 | Tools from MCP servers | `[?]` | AGT-02 | 2026-11-03 | Client only, or also expose agents as an MCP server? |
| AGT-08 | Run observability: run trace linking gateway calls; per-run cost rollup | `[ ]` | AGT-03, OBS-01, COST-02 | 2026-11-03 | |

## Operations

| ID | Item | Status | Needs | Updated | Notes |
|---|---|:-:|---|---|---|
| OPS-01 | k6 load suite against the mock provider; measure the overhead budget | `[ ]` | GW-02, FND-03 | 2026-11-03 | Confirms or revises the VISION target (p99 overhead at most 15 ms). |
| OPS-02 | Multi-replica correctness: two gateway replicas, consistent limits and cache; Redis failover behaviour | `[ ]` | RL-02, CACHE-01 | 2026-11-03 | |
| OPS-03 | Container images and Kubernetes manifests (Helm), autoscaling on concurrency | `[ ]` | OPS-01 | 2026-11-03 | |
| OPS-04 | Public demo: one-command scenario, README walkthrough, architecture write-up | `[ ]` | AGT-04, COST-03, GRD-02, RTE-02 | 2026-11-03 | The "Done for v1" scenario in VISION. |
| OPS-05 | Admin web UI | `[?]` | | 2026-11-03 | Out of v1 per VISION; is a UI worth it beyond Grafana and the admin API? |
