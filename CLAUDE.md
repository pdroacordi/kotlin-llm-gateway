# agent-gateway

An open-source LLM gateway (model routing, exact and semantic caching, per-team rate limits, guardrails, tracing, per-call cost) and an agent runtime that registers and orchestrates agents on top of it. Kotlin + Spring Boot. Built clean-room (rule 1).

| Question | Where |
|---|---|
| What is the product, and why? | [`docs/VISION.md`](docs/VISION.md) |
| What is built, what is next? | [`docs/roadmap.md`](docs/roadmap.md) |
| What must item X do? | [`docs/specs/`](docs/specs/) `<ID>-<slug>.md` |
| Why is the system shaped this way? | [`docs/DECISIONS.md`](docs/DECISIONS.md) (cross-cutting ADRs; item-local ADRs live in the spec) |
| How does system X work, and what must not break? | [`docs/knowledge/systems/`](docs/knowledge/systems/) (table below) |
| What broke before, what surprised us, how did the last run go? | [`docs/knowledge/`](docs/knowledge/README.md) `patterns/`, `bugs/`, `gotchas/`, `runs/` |
| What does term X mean? | [`docs/GLOSSARY.md`](docs/GLOSSARY.md) |
| How does a request or a run flow? | [`docs/diagrams/`](docs/diagrams/README.md) |

This file holds only the rules that apply everywhere. **Before changing a system, read its systems entry.**

## Hard rules

1. **Clean-room.** Nothing from any employer: no code, config, internal service or host names, metric names, prompts, dashboards or architecture documents, whether from files or from memory. Design from public sources (papers, OSS projects, vendor docs) and cite them in the spec. The pre-commit hook scans staged additions against `.clean-room-denylist` (local, gitignored, one term per line). When unsure whether something is internal knowledge, ask.
2. **No secrets** in the repo, logs, traces, test fixtures or prompts to other tools. Provider keys come from the environment.
3. **Preserve work you did not do.** No stash, reset or checkout over changes that are not yours; never `git add -A`. Stage explicit paths.
4. **Commit and push only as the skills say.** `feature-loop` commits each finished item locally; nothing is pushed unless the user asks. Never `--no-verify`.
5. **Decide and record.** Inside an item, take the engineering decision, record it as an ADR (local in the spec, cross-cutting in `DECISIONS.md`) and continue. Ask the user only about product scope, money (paid provider calls, accounts), licensing, public naming, or a `[?]` row.

## Architecture

Hexagonal, in Gradle modules ([ADR-005](docs/DECISIONS.md#adr-005)). Dependencies point inward:

```
gateway-app ──▶ gateway-core ◀── gateway-providers   (OpenAI, Anthropic, mock)
     └────────▶ gateway-infra ──▶ gateway-core       (Redis, Postgres, Kafka, OTel)
agent-app   ──▶ agent-core                           (calls the gateway over HTTP, like any client)
cli                                                  (HTTP client of both apps, imports neither; ADR-012)
```

The module list is a target; FND-01 sets the real one and updates this section.

- **Core modules are plain Kotlin.** No Spring, Reactor, Netty, JDBC/R2DBC or HTTP-client imports in `*-core`. They hold domain types, ports (interfaces) and pure policies (routing, limits, cost, cache keys). Konsist tests enforce it.
- **One port per capability** (`ChatModelPort`, `RateLimiterPort`, `ResponseCachePort`, `GuardrailPort`, `UsageSink`, ...). Only the adapter sees a provider's or library's types.
- **Wire in the app module only.** `@Configuration` and `@Component` live in `*-app`; core classes take collaborators by constructor.
- **The agent runtime is a gateway client.** It never imports gateway modules; it calls `/v1/chat/completions` with its own team key, so its traffic is limited, cached, guarded, traced and billed like anyone's.
- **Data over branches.** Routes, the price catalog, guardrail policies and agent definitions are versioned data, not `when` branches.
- **Non-blocking hot path.** WebFlux + coroutines ([ADR-006](docs/DECISIONS.md#adr-006)): `suspend` for one value, `Flow` for a stream. Never block an event-loop thread; blocking I/O runs on `Dispatchers.IO` inside an adapter. BlockHound runs in the integration tests.

## Kotlin conventions

- Kotlin official style (ktlint), 4-space indent, 120 columns. Identifiers in English.
- Domain types are immutable `data class`es; IDs and quantities are `value class`es (`TeamId`, `TokenCount`).
- Expected failures are sealed result types in core (`RateLimited`, `GuardrailBlocked`, `ProviderUnavailable`). Exceptions are for bugs and adapter boundaries. The app maps both to the OpenAI error envelope in one place.
- No `!!`. No `lateinit` outside tests. No `GlobalScope`. Never swallow `CancellationException`.
- Money and prices are never `Double` or `Float`.
- Time comes from an injected `Clock`/`TimeSource`; core never calls `Instant.now()` or `System.currentTimeMillis()`.
- Every outbound call has an explicit timeout. Retries and fallbacks happen only before the first byte reaches the client.
- Configuration is `@ConfigurationProperties` data classes validated at startup, not scattered `@Value`.

## Comments

Code says what. A comment says only what the code cannot:

- **Why** a non-obvious choice was made, in one sentence. `// Reserve before the call: a stream's usage is known only at the end.`
- **A constraint** a later edit could break. `// Must match the key layout in rate_limit.lua.`
- **Units or range** the name does not carry: `ms`, `tokens/min`, `0..1`, `micro-USD`.
- **A pointer** to a spec, an ADR or a `docs/knowledge/` entry.

Do not write restatements of the name or signature, the history of the code, emphasis in capitals, or commented-out code. KDoc is one or two sentences; anything longer belongs in `docs/knowledge/`, linked in one line. The same rules apply to documentation: state the fact, the number and the reason, without narrative.

## Systems

Read the entry before touching the system. The rule beside each is the one a change is most likely to break. Until a system is built the row is its contract; the item that builds it creates the entry and links it here in the same change.

| System | Entry | Never break |
|---|---|---|
| Auth and teams | (GW-04) | Every request resolves to exactly one team before any other stage. API keys are stored as HMAC hashes and shown once. |
| Provider adapters | (GW-01) | Provider types never cross the port. A stream is translated chunk by chunk, never buffered whole. Usage comes from the provider; an estimate is used only when the provider omits it, and is flagged `estimated`. |
| Routing | (RTE-01) | Routing is a pure function of (request, route table, provider health, budget state). Fallback happens only before the first byte is sent. |
| Rate limiting | (RL-01) | One atomic Redis script per decision; never read-then-write from the JVM. Limits are keyed by team, never by API key alone. |
| Caching | (CACHE-01) | The key covers team, resolved model and every parameter that changes output. A hit never crosses teams. A blocked or errored response is never stored. |
| Guardrails | (GRD-01) | Every rule declares fail-open or fail-closed. Input rules run before any provider call; the cache key is computed after redaction. |
| Observability | (OBS-01) | Spans follow the OpenTelemetry GenAI conventions. Prompt and completion text are never span attributes or log fields unless the team opted in, and then redacted. |
| Cost | (COST-01) | Cost = catalog price in effect at request time x provider-reported usage. Usage events are idempotent by request ID; the ledger is append-only. |
| Agent runtime | (AGT-01) | A step's state is persisted before its side effect; a resumed run never repeats a completed step. Every run has step, token and time limits. Tool HTTP calls go only to registered hosts. |

## Working on the roadmap

- Pick work from [`docs/roadmap.md`](docs/roadmap.md); its header says how. Multi-item runs use the `feature-loop` skill.
- Start every session with the `preflight` skill (the SessionStart hook runs its script and prints only problems).
- Every code change passes `verify-gates` before it is committed or marked done. A change observable over HTTP is also exercised by `gateway-smoke`; a change to a threshold, classifier or routing policy by `ai-eval`.
- Living docs are updated in the order of the `docs-update` skill. A user decision is recorded in their words, with the date.

## Testing

Commands exist once FND-01 lands.

```bash
./gradlew test                    # unit (core, no Spring context) + integration (Testcontainers)
./gradlew koverVerify             # coverage thresholds
./gradlew ktlintCheck detekt      # style + static analysis
./gradlew build                   # everything, including the Konsist architecture tests
docker compose -f deploy/compose.yaml up -d   # local stack (FND-02)
```

- TDD for core: a failing test first, the minimal code to pass, then refactor. Core tests are plain JUnit 5 + MockK + `kotlinx-coroutines-test`, no Spring context.
- Integration tests use Testcontainers (Postgres, Redis, Kafka-compatible broker) and WireMock or `mock-provider`.
- **Tests never call a real provider.** Live tests are tagged `live`, read keys from the environment, never run in CI, and need the user's go-ahead (they cost money).
- Coverage: at least 80% of lines in `*-core`, 70% overall (Kover).

## Local ports

Reserved so this stack can run beside other projects. FND-02 wires them in `deploy/compose.yaml`.

| Service | Port | Service | Port |
|---|---|---|---|
| gateway-app | 8080 | Redpanda (Kafka API) | 19092 |
| agent-app | 8081 | OTel collector (gRPC / HTTP) | 4317 / 4318 |
| mock-provider | 8090 | Jaeger UI | 16686 |
| Postgres | 5440 | Prometheus | 9090 |
| Redis | 6390 | Grafana | 3001 |

## Knowledge base

[`docs/knowledge/`](docs/knowledge/README.md) is the project's engineering memory. **Read the relevant entries before acting; write a new entry after learning something not already there.** `gateway-architect` writes `patterns/`, `gateway-reviewer` writes `bugs/` and `gotchas/`, `gateway-tester` writes `runs/`, and whoever changes a system updates its `systems/` entry.

## Project structure (target)

```
gateway-core/        domain, ports, pure policies
gateway-providers/   provider adapters (openai, anthropic)
gateway-infra/       redis, postgres, kafka, otel adapters
gateway-app/         Spring Boot: OpenAI-compatible API, admin API, wiring
agent-core/          agent definitions, run/step state machine, orchestration patterns
agent-app/           Spring Boot: registry API, run executor, gateway client
cli/                 the `agw` command: chat, agents and runs, admin (HTTP only)
mock-provider/       deterministic provider fake (latency, errors, streams, usage)
architecture-tests/  Konsist rules
evals/               labelled datasets and the eval runner (cache, guardrails, routing)
tools/smoke/         smoke scenarios; tools/load/ k6 scripts
deploy/              compose.yaml, OTel, Grafana provisioning; later Helm
docs/                living docs (this table's links)
```

## Commits

`type(scope): summary`, imperative, English, at most 72 characters. Types: `feat fix perf refactor test docs build ci chore style revert`. Scope is the area (`gateway`, `routing`, `ratelimit`, `cache`, `guardrails`, `obs`, `cost`, `agents`, `docs`, `build`). `feat`, `fix` and `perf` bodies end with `Roadmap: <ID>`. No co-author trailers. `.githooks/` checks this and runs the clean-room and docs checks (`git config core.hooksPath .githooks`).
