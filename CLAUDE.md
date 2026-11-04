# kotlin-llm-gateway

An open-source LLM gateway (model routing, exact and semantic caching, per-team rate limits, guardrails, tracing, per-call cost) and an agent runtime that registers and orchestrates agents on top of it. Kotlin + Spring Boot. Built clean-room (rule 1). Base package `io.github.pdroacordi.llmgateway`; CLI `llmgw` (ADR-013).

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

1. **Clean-room.** Nothing from any employer: no code, config, internal service or host names, metric names, prompts, dashboards or architecture documents, whether from files or from memory. Design from public sources (papers, OSS projects, vendor docs) and cite them in the spec. When unsure whether something is internal knowledge, ask.
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
mock-provider                                        (provider fake on :8090; imports nothing of ours; FND-03)
test-support                                         (Testcontainers fixtures; integrationTest only; imports nothing of ours; FND-02)
```

The graph above is the target. Built today: `gateway-core` (request IDs, the error model, the chat domain and teams, FND-04, GW-01, GW-04), `gateway-providers` (the OpenAI and Anthropic adapters over `.upstream`, GW-01, GW-03), `gateway-infra` (the Postgres team store and its Caffeine cache, GW-04), `gateway-app`, `mock-provider` (FND-03, a Spring Boot app that imports nothing of ours), `test-support` (FND-02, Testcontainers fixtures for `integrationTest` sources only), `architecture-tests`. [ADR-014](docs/DECISIONS.md#adr-014) says which item creates each other module and its root package. Adding a module: `include` it in `settings.gradle.kts`, apply exactly one convention plugin from `build-logic/`, add a `kover(project(...))` line to the root `build.gradle.kts` if it has production code, and put every file under its root package from the ADR-014 table.

- **Core modules are plain Kotlin.** No Spring, Reactor, Netty, JDBC/R2DBC or HTTP-client imports in `*-core`. They hold domain types, ports (interfaces) and pure policies (routing, limits, cost, cache keys). Konsist tests enforce it.
- **One port per capability** (`ChatModelPort`, `RateLimiterPort`, `ResponseCachePort`, `GuardrailPort`, `UsageSink`, ...). Only the adapter sees a provider's or library's types.
- **Wire in the app module only.** `@Configuration` and `@Component` live in `*-app`; core classes take collaborators by constructor.
- **The agent runtime is a gateway client.** It never imports gateway modules; it calls `/v1/chat/completions` with its own team key, so its traffic is limited, cached, guarded, traced and billed like anyone's.
- **Data over branches.** Routes, the price catalog, guardrail policies and agent definitions are versioned data, not `when` branches.
- **Non-blocking hot path.** WebFlux + coroutines ([ADR-006](docs/DECISIONS.md#adr-006)): `suspend` for one value, `Flow` for a stream. Never block an event-loop thread; blocking I/O runs on `Dispatchers.IO` inside an adapter. BlockHound runs in every app module's test JVMs and in `gateway-providers`'.

## Kotlin conventions

- Kotlin official style (ktlint), 4-space indent, 120 columns. Identifiers in English.
- Domain types are immutable `data class`es; IDs and quantities are `value class`es (`TeamId`, `TokenCount`).
- Expected failures are sealed result types in core (`RateLimited`, `GuardrailBlocked`, `ProviderUnavailable`). Exceptions are for bugs and adapter boundaries. The app maps both to the OpenAI error envelope in one place. At the HTTP edge a handler or filter raises a `GatewayError` as `GatewayErrorException`; only `<app root>.error` maps exceptions to the envelope (ARCH-10, ADR-015).
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
| Request context and errors | [request-context-and-errors](docs/knowledge/systems/request-context-and-errors.md) | Every response WebFlux handles carries a gateway-generated `x-request-id`; every error body is the OpenAI envelope written only by `gateway.app.error`; a 5xx body never carries an exception message. |
| Local stack and test fixtures | [local-stack](docs/knowledge/systems/local-stack.md) | Host ports bind 127.0.0.1 from LLMGW_*_PORT with the defaults below; an image tag changes in deploy/compose.yaml and StackImages together; integration tests never touch the dev stack. |
| Mock provider | [mock-provider](docs/knowledge/systems/mock-provider.md) | The same request always gets the same content and usage; a credential value is never stored, logged or returned; a scenario step is consumed by exactly one request. |
| Auth and teams | [auth-and-teams](docs/knowledge/systems/auth-and-teams.md) | Every `/v1` request resolves to exactly one team, and every `/admin` request to the admin key, before any other stage. Team keys are stored as HMAC digests and shown once. A store outage is never a 401. |
| Provider adapters | [provider-adapters](docs/knowledge/systems/provider-adapters.md) | Provider types never cross the port. A stream is translated chunk by chunk, never buffered whole. Usage comes from the provider; an estimate is used only when the provider omits it, and is flagged `estimated`. A member an adapter cannot honour is a 400 with no upstream call, never a silent drop. |
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

```bash
./gradlew test                                # unit (core, no Spring context) + Spring-context tests; no Docker
./gradlew integrationTest                     # tests that need Docker (Testcontainers), per module (ADR-016)
./gradlew :architecture-tests:test            # Konsist architecture rules ARCH-1 to ARCH-13
./gradlew koverVerify                         # coverage thresholds
./gradlew ktlintCheck detektMain detektTest detektIntegrationTest detektTestFixtures   # style + static analysis (detekt with type resolution)
./gradlew ktlintFormat                        # fix style
./gradlew build                               # everything, integrationTest included: needs Docker running
docker compose -f deploy/compose.yaml up -d --wait   # local stack; overrides in deploy/.env
bash tools/smoke/stack-check.sh   # stack health, OTLP to Jaeger and Prometheus, Grafana datasources
bash tools/smoke/live-openai.sh   # paid, real OpenAI API, key in .env (.env.example); only with the user's go-ahead
```

Gradle runs on JDK 21+ and provisions the JDK 25 toolchain itself.

`gateway-app` needs Postgres to start (`docker compose -f deploy/compose.yaml up -d --wait postgres`), in the `local` profile too. It also needs `LLMGW_KEY_HMAC_SECRET`, `LLMGW_ADMIN_KEY` and the key of each enabled provider (`OPENAI_API_KEY` by default; Anthropic is off unless enabled, and then needs `ANTHROPIC_API_KEY`), or the `local` profile, which supplies fake secrets, enables both providers and points them at `mock-provider` with fake keys.

- TDD for core: a failing test first, the minimal code to pass, then refactor. Core tests are plain JUnit 5 + MockK + `kotlinx-coroutines-test`, no Spring context.
- Integration tests use Testcontainers (Postgres, Redis, Kafka-compatible broker) and WireMock or `mock-provider`. Tests that need Docker live in `src/integrationTest` and run in the `integrationTest` suite (convention `llmgw.integration-test`, wired into `check`); without a daemon they fail, never skip (ADR-016). Shared, lazily started containers come from `test-support` (`SharedPostgres`, `SharedRedis`, `SharedRedpanda`, `SharedMockProvider`; images in `StackImages`, kept equal to `deploy/compose.yaml` by `StackContractTest`). A module adds `integrationTestImplementation(project(":test-support"))`, and `mockProvider(project(":mock-provider"))` for the mock image. `mock-provider` runs as a container built from its boot jar and `mock-provider/Dockerfile` (consume the `mockProviderBootJar` configuration).
- **Tests never call a real provider.** Live checks read keys from the environment or `.env`, never run in CI, and need the user's go-ahead (they cost money): JUnit ones are tagged `live`, and `tools/smoke/live-openai.sh` checks the OpenAI adapter.
- Coverage: at least 80% of lines in `*-core`, 70% overall (Kover).

## Local ports

Reserved so this stack can run beside other projects. `deploy/compose.yaml` binds each to 127.0.0.1 from an `LLMGW_*_PORT` variable with this default; a worktree sets `COMPOSE_PROJECT_NAME` and the ports in `deploy/.env` (copy `deploy/.env.example`).

| Service | Port | Service | Port |
|---|---|---|---|
| gateway-app | 8080 | Redpanda (Kafka API) | 19092 |
| agent-app | 8081 | OTel collector (gRPC / HTTP) | 4317 / 4318 |
| mock-provider | 8090 | Jaeger UI | 16686 |
| Postgres | 5440 | Prometheus | 9090 |
| Redis | 6390 | Grafana | 3301 |

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
cli/                 the `llmgw` command: chat, agents and runs, admin (HTTP only)
mock-provider/       deterministic provider fake (latency, errors, streams, usage)
architecture-tests/  Konsist rules
test-support/        Testcontainers fixtures for integrationTest suites
build-logic/         convention plugins (included build)
gradle/              wrapper, version catalog (libs.versions.toml)
config/detekt/       detekt overrides
evals/               labelled datasets and the eval runner (cache, guardrails, routing)
tools/smoke/         smoke scenarios; tools/load/ k6 scripts
deploy/              compose.yaml, OTel collector, Prometheus, Grafana provisioning; later Helm
docs/                living docs (this table's links)
```

## Commits

`type(scope): summary`, imperative, English, at most 72 characters. Types: `feat fix perf refactor test docs build ci chore style revert`. Scope is the area (`gateway`, `routing`, `ratelimit`, `cache`, `guardrails`, `obs`, `cost`, `agents`, `docs`, `build`). `feat`, `fix` and `perf` bodies end with `Roadmap: <ID>`. No co-author trailers. `.githooks/` checks this and runs the docs check (`git config core.hooksPath .githooks`).
