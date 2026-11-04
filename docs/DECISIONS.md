# Decisions (cross-cutting ADRs)

Architecture Decision Records that affect several roadmap items or the whole system. A decision local to one item lives in its spec (`docs/specs/<ID>-*.md`, ADR section).

Format: Status, Date, Context, Options, Decision, Consequences. Append-only: a changed decision gets a dated revision line or a new ADR that supersedes it. `Proposed` ADRs name the item that settles them.

---

<a id="adr-001"></a>
## ADR-001: Clean-room development

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The author works on similar systems professionally. The repository is public and must not contain or derive from any employer's code, configuration, internal names or documents.

### Options
- **A)** No explicit policy; rely on care.
- **B)** Clean-room: design only from public sources, cite them in each spec, and scan every commit against a local denylist of employer terms.

### Decision
**B.** CLAUDE.md rule 1. `.clean-room-denylist` is local and gitignored, because listing internal terms in the repo would itself leak them.

### Consequences
- Specs carry a `references` list of public sources.
- 2026-11-03: the automatic scan was removed (user: "Remove the denylist"; the project is personal and the check had no terms to scan). `scripts/clean-room-scan.sh` and `.clean-room-denylist` no longer exist; the rule stands as guidance in CLAUDE.md rule 1 and in the specs' `references`.
- When the origin of an idea is unclear, the agent asks instead of writing it.

---

<a id="adr-002"></a>
## ADR-002: Kotlin + Spring Boot

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The project must show distributed-backend and applied-AI engineering in a stack used at scale in industry, and fit the author's JVM background.

### Options
- **A)** Kotlin + Spring Boot.
- **B)** Go (the usual language of high-performance gateways).
- **C)** Java 21 + Spring Boot (virtual threads, Spring AI).
- **D)** Go gateway + Python agent runtime.

### Decision
**A.** User, 2026-11-03: "Kotlin + Spring Boot (Recommended)".

### Consequences
- Coroutines and `Flow` for the async model (ADR-006); Spring ecosystem for resilience (Resilience4j), metrics (Micrometer) and tracing (OpenTelemetry).
- FND-01 pins the then-current stable Kotlin, Spring Boot and LTS JDK.
- JVM startup and memory are higher than Go; OPS-03 accounts for it in the container sizing.
- 2026-11-03 (FND-01): pinned Kotlin 2.4.21, Spring Boot 4.1.2, JDK 25 toolchain; see ADR-FND-01-001 and ADR-FND-01-003.

---

<a id="adr-003"></a>
## ADR-003: English for code and documentation

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The repository is open source and read by international reviewers.

### Options
- **A)** English. **B)** Portuguese.

### Decision
**A.** User, 2026-11-03: "Inglês (Recommended)". Conversations with the author may be in Portuguese; everything committed is English.

### Consequences
Brazilian-specific features (CPF and CNPJ detection, GRD-02) keep their proper names.

---

<a id="adr-004"></a>
## ADR-004: OpenAI-compatible API surface

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
Adoption cost decides whether teams route through a gateway. Most SDKs and tools already speak the OpenAI Chat Completions API.

### Options
- **A)** A gateway-specific API.
- **B)** OpenAI-compatible endpoints, with gateway features in `x-gateway-*` headers and optional extension fields.
- **C)** Both.

### Decision
**B.** `/v1/chat/completions`, `/v1/embeddings`, `/v1/models`. The admin API lives under `/admin/v1`, the agent API under `/agents/v1`.

### Consequences
- Adapters translate other providers (Anthropic first, GW-03) to and from the OpenAI shape, including streams and tool calls.
- Unknown request fields are tolerated and passed on where the provider supports them.
- Features with no OpenAI equivalent (cache bypass, cost, request ID) travel in headers.
- 2026-11-03: v1 providers are OpenAI and Anthropic only (user: "Keep the two"). Another provider (Gemini, Bedrock, ...) or another inbound format (Anthropic Messages API) is new scope and needs the user's go-ahead; GW-07 (local models) stays `[?]`.

---

<a id="adr-005"></a>
## ADR-005: Hexagonal architecture in Gradle modules

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The gateway composes many infrastructure pieces (providers, Redis, Postgres, a broker, OTel) around policies that must be unit-testable and fast: routing, limits, cost, cache keys, guardrail verdicts.

### Options
- **A)** One Spring Boot module, packages by layer.
- **B)** One module, packages by feature, boundaries by convention.
- **C)** Gradle modules per hexagon side (`*-core` plain Kotlin with ports; adapters; `*-app` wiring), boundaries enforced by the build and by Konsist tests.

### Decision
**C.** Module list in CLAUDE.md (target), set by FND-01.

### Consequences
- Core logic is tested without a Spring context or containers, which keeps the TDD loop fast.
- Adding a provider or a store is a new adapter, not a change to core.
- More build configuration up front; FND-01 absorbs it with a version catalog and convention plugins.

---

<a id="adr-006"></a>
## ADR-006: WebFlux with coroutines on the hot path

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The gateway holds many concurrent, long-lived streaming responses (SSE) whose latency is dominated by the provider. It must translate streams chunk by chunk and cancel upstream work when a client disconnects.

### Options
- **A)** Spring MVC with virtual threads: simple blocking code, good throughput; stream translation and cancellation propagation are less natural.
- **B)** WebFlux with Reactor types throughout.
- **C)** WebFlux with Kotlin coroutines: `suspend` and `Flow` in code, Reactor only at the framework edge.

### Decision
**C.**

### Consequences
- Blocking libraries are isolated in adapters on `Dispatchers.IO`; BlockHound in the integration tests catches violations.
- 2026-11-03: BlockHound runs in every app module's test JVMs through `llmgw.spring-app`, not only in integration tests.
- Whether persistence uses R2DBC or JDBC on `Dispatchers.IO` is decided in GW-04 (local ADR).
- Reviewers check cancellation and structured concurrency (gateway-reviewer, priority 1).

---

<a id="adr-007"></a>
## ADR-007: Living documentation and the agent harness

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The project is built with AI coding agents. Without durable docs every session re-derives decisions and repeats mistakes.

### Options
- **A)** Feature-file hub (an index plus a heavy file per feature).
- **B)** Roadmap table plus a retrieval-shaped knowledge base.
- **C)** Hybrid: roadmap with permanent IDs; a spec per non-trivial item with local ADRs and an append-only log; cross-cutting ADRs here; a knowledge base (systems contracts, patterns, bugs, gotchas, runs) that agents read before acting and write after learning.

### Decision
**C.** User, 2026-11-03: "Híbrido (Recommended)". Claude Code is the only supported agent tool (user, 2026-11-03: "Só Claude Code").

### Consequences
- `scripts/docs-check.sh` (pre-commit and CI) keeps the indexes and IDs consistent.
- Skills: `preflight`, `feature-loop`, `verify-gates`, `gateway-smoke`, `ai-eval`, `docs-update`, `diagram-update`, `skill-library`. Agents: `gateway-architect`, `gateway-reviewer`, `gateway-tester`.

---

<a id="adr-008"></a>
## ADR-008: Redis for rate limits and the exact cache

- **Status:** Proposed (settled by RL-01)
- **Date:** 2026-11-03

### Context
Limits must hold across replicas with one round trip per decision; the exact cache needs TTLs and fast lookups.

### Options
- **A)** Redis: atomic Lua scripts, TTLs, cluster mode.
- **B)** Postgres: simpler stack, slower and contention-prone for per-request counters.
- **C)** In-process limits with gossip: no extra dependency, approximate across replicas.

### Decision
To be taken in RL-01, including the algorithm (token bucket, sliding window, GCRA) and the behaviour when Redis is unavailable.

---

<a id="adr-009"></a>
## ADR-009: Vector store for the semantic cache

- **Status:** Proposed (settled by CACHE-02)
- **Date:** 2026-11-03

### Options
- **A)** Redis vector search (one store for both caches).
- **B)** Postgres with pgvector (one store for vectors and the rest of the data).
- **C)** An in-process HNSW index per replica.

### Decision
To be taken in CACHE-02 with the `semantic-cache` eval (CACHE-03): latency, recall at the chosen threshold, operational cost.

---

<a id="adr-010"></a>
## ADR-010: Transport of usage events

- **Status:** Proposed (settled by COST-02)
- **Date:** 2026-11-03

### Options
- **A)** A Kafka-compatible broker (Redpanda in dev), consumed by an idempotent ledger writer.
- **B)** A transactional outbox in Postgres, polled by the ledger writer.
- **C)** A direct write to the ledger on the request path.

### Decision
To be taken in COST-02. Constraint either way: the request path never waits on the ledger, and an event is applied once even if delivered twice.

- 2026-11-03 (FND-02): the dev stack and `test-support` run Redpanda v26.2.6 so option A can be evaluated; if COST-02 picks B, the `redpanda` service and `SharedRedpanda` are removed. Licences accepted for local and CI use only (images unmodified, nothing redistributed): Redpanda BSL 1.1 and Redis 8 (tri-licence incl. AGPLv3), user: "Redpanda + Redis 8 (Recommended)".

---

<a id="adr-011"></a>
## ADR-011: The agent runtime is a client of the gateway

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
Agents make many model calls. They need the same limits, caching, guardrails, tracing and cost accounting as any other traffic.

### Options
- **A)** The runtime embeds gateway modules and calls providers in-process.
- **B)** The runtime calls the gateway over HTTP with its own team key, propagating trace context and run and step IDs.

### Decision
**B.**

### Consequences
- `agent-*` modules never import `gateway-*` modules (Konsist rule).
- One extra network hop per model call, accepted for the uniform controls; AGT-08 links run traces to gateway spans.

---

<a id="adr-012"></a>
## ADR-012: A CLI client that talks only HTTP

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The product is two HTTP services. A back end alone is hard to demonstrate: there is nothing to watch while an agent runs. User, 2026-11-03: "Yes, add a CLI".

### Options
- **A)** No client; demo with `curl` and Grafana.
- **B)** A CLI module that imports the core modules and calls them in-process.
- **C)** A CLI that is a pure HTTP client of the public and admin APIs, like any other client.

### Decision
**C.** Module `cli/`, command `llmgw`. Framework and packaging (JVM or native image) are decided in CLI-01.

### Consequences
- `cli` never imports `gateway-*` or `agent-*` modules (Konsist rule); whatever the CLI needs is first an API, so the CLI exercises the same contract as any client.
- Live views (streamed chat, agent steps) need streaming endpoints; CLI-02 adds the run events stream to agent-app.

---

<a id="adr-013"></a>
## ADR-013: Project name and base package

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
The working name `agent-gateway` is confusable with agentgateway (agentgateway.dev), an established open-source AI gateway under the Linux Foundation. The user wants a name "tão claro quanto agent-gateway, para quem vê já soubesse o que é".

### Options
- **A)** Keep `agent-gateway`.
- **B)** A metaphor name (`portaria`, `comporta`): distinctive, not self-describing.
- **C)** A descriptive name with no exact GitHub collision: `kotlin-llm-gateway` or `llm-control-plane` (`switchyard` was rejected: NVIDIA's NeMo Switchyard).

### Decision
**C, `kotlin-llm-gateway`** (user, 2026-11-03: "kotlin-llm-gateway (Recommended)"). Base package `io.github.pdroacordi.llmgateway` (GitHub user `pdroacordi`); CLI command `llmgw`. Module names (`gateway-core`, `agent-app`, ...) are unchanged.

### Consequences
- The local folder is still `agent-gateway`; renaming it is a manual step outside a session. The GitHub repository is created as `kotlin-llm-gateway`.

---

<a id="adr-014"></a>
## ADR-014: Modules are created when first needed; one root package per module

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
ADR-005 lists ten target modules. FND-01 builds the Gradle skeleton and the Konsist rules that keep the hexagon (ADR-005), the agent runtime (ADR-011) and the CLI (ADR-012) apart. Most target modules get no code for several items (agent modules with AGT-01, `cli` with CLI-01, `evals` with CACHE-03). An empty module still costs build configuration, a Konsist layer with no files fails `assertArchitecture` ("Layer X doesn't contain any files", Konsist docs), and an app shell would pre-empt choices that belong to later items (CLI framework in CLI-01, the shape of `mock-provider` in FND-03). Import rules keyed by package only mean something if every module owns a distinct package root.

### Options
- **A)** Create all ten modules in FND-01 with placeholder sources.
- **B)** FND-01 creates only `gateway-core` (empty: the dependency target of `gateway-app` and the subject of the core-purity rule), `gateway-app` and `architecture-tests`. Every other module is created by the first item that puts code in it. The Konsist rules are keyed by a module table that already lists every target module with its root package and role, so a new module is under the rules from its first file, and an unlisted module fails a classification test.
- **C)** B plus empty `agent-core` and `agent-app`, so the ADR-011 rule has real files to scan from day one.

### Decision
**B.** Each rule is proven to fire by a violating fixture ([`patterns/architecture-rule-with-violating-fixture`](knowledge/patterns/architecture-rule-with-violating-fixture.md)), so the ADR-011 and ADR-012 rules are live before their modules exist, which removes C's only advantage. Root packages under `io.github.pdroacordi.llmgateway` (ADR-013):

| Module | Root package | Role | Created by |
|---|---|---|---|
| `gateway-core` | `.gateway.core` | core | FND-01 |
| `gateway-providers` | `.gateway.providers` | adapter | GW-01 |
| `gateway-infra` | `.gateway.infra` | adapter | first item with infra code (FND-02 or GW-04) |
| `gateway-app` | `.gateway.app` | app | FND-01 |
| `agent-core` | `.agent.core` | core | AGT-01 |
| `agent-app` | `.agent.app` | app | AGT-01 |
| `cli` | `.cli` | client | CLI-01 |
| `mock-provider` | `.mockprovider` | app (ADR-FND-03-003) | FND-03 |
| `evals` | `.evals` | tool | CACHE-03 |
| `architecture-tests` | `.architecture` | tool | FND-01 |
| `test-support` | `.testsupport` | tool (ADR-017) | FND-02 |

### Consequences
- The item that creates a module includes it in `settings.gradle.kts`, applies exactly one convention plugin, adds it to the merged Kover report in the root build, and puts every file under the module's root package. A module missing from the table, or a role change, is a local ADR in that item's spec plus an edit of the table in `architecture-tests` and here.
- Sub-packages inside a root (by feature, e.g. `.gateway.core.routing`) are the creating item's choice.
- Spring wiring annotations are allowed only in modules with the `app` role; if FND-03 makes `mock-provider` a Spring Boot app it changes that module's role, with a local ADR.
- Details and the rule list: [`specs/FND-01-gradle-skeleton.md`](specs/FND-01-gradle-skeleton.md) (ADR-FND-01-005, ADR-FND-01-007).
- 2026-11-03 (FND-03): `mock-provider` takes role `app` (a Spring Boot WebFlux service on port 8090) with an empty `dependsOn`, so it imports none of our packages; ARCH-6 allows its wiring and ARCH-10 confines its error mapper to `.mockprovider.error`. See [ADR-FND-03-003](specs/FND-03-mock-provider.md).
- 2026-11-03 (FND-02): `test-support` added (role tool, ADR-017); `gateway-infra` is not created by FND-02 and comes with GW-04 or the first item with infra code.

---

<a id="adr-015"></a>
## ADR-015: Request ID and error contract

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
GW-01, GW-02, GW-04, RL-01, GRD-01, COST-02, COST-03, OBS-01 and CLI-01 all produce or consume request IDs and error responses. Each of them needs the same ID semantics and the same error shape:
- CLAUDE.md makes usage events idempotent by request ID.
- ADR-004 promises OpenAI compatibility: OpenAI returns `x-request-id`, accepts `X-Client-Request-Id`, and SDKs read `{"error":{"message","type","param","code"}}` and pick the exception class by status.
- CLAUDE.md asks for one place that maps failures to that envelope.

### Options
Full options and reasoning in [FND-04](specs/FND-04-request-context-and-errors.md), ADR-FND-04-001 to -007. In short:
- Request ID: accept the client's value; always generate; or a gateway-specific header.
- Error format: the OpenAI envelope or RFC 9457 problem details.
- Mapping: controller advice plus Boot's handler, or one `WebExceptionHandler`.

### Decision
- **Request ID:**
  - The gateway generates a UUIDv7 for every request and returns it in `x-request-id` on every response WebFlux handles, errors and 404s included.
  - A request's own `x-request-id` is ignored.
  - A client may send `x-client-request-id`: 1 to 512 printable ASCII characters, echoed back, otherwise 400.
  - The request ID is the usage-event idempotency key and is never the trace ID. OBS-01 records it on the server span as `http.response.header.x-request-id`.
- **Errors:**
  - Every error body is the OpenAI envelope, exactly four members, `application/json`, never problem+json.
  - Expected failures are variants of the sealed `GatewayError` in `gateway-core`. One table there gives each variant its status, `type` (mirroring OpenAI's value for the same condition) and `code` (the stable discriminator).
  - A shipped (status, type, code) row never changes; a new condition gets a new code.
  - Messages never carry request content or exception messages; 5xx messages end with the request ID.
  - The reserved rows for 401, 403, 404 model, 429, 429 quota, guardrail 400, 502, 503 and 504 are in FND-04 section 4.
- **One mapper per app module:**
  - It lives only in `<app root>.error` (Konsist ARCH-10): a `WebExceptionHandler` that replaces Spring Boot's default, with no `@ControllerAdvice` anywhere.
  - Handlers and filters raise `GatewayErrorException(GatewayError)`.
  - After the response is committed (streams), the error becomes an SSE error event carrying the same envelope (GW-02).

### Consequences
- Each later item adds its variant and table row and inherits the request ID, the envelope and `x-request-id` on its errors without further work.
- AGT-01 decides whether `/agents/v1` uses the same envelope. The default is yes, so the CLI parses one shape. agent-app cannot import `gateway-core` (ADR-011), so it keeps its own small table; ARCH-10 already confines its mapper to `.agent.app.error`.
- COST-02 can store the request ID as a Postgres `uuid` with time-ordered inserts.

---

<a id="adr-016"></a>
## ADR-016: Tests that need Docker run in their own suite

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
From FND-03 on, some tests need a Docker daemon: the mock-provider image test (ADR-FND-03-002), then the Testcontainers base classes of FND-02 (Postgres, Redis, the broker) and every adapter's integration tests. Mixing them into `test` makes the fast TDD loop depend on Docker and slows it down.

### Options
- **A)** Everything in `test`; Docker always required.
- **B)** Docker tests skipped when Docker is absent (`disabledWithoutDocker`): a missing daemon turns into a silent pass.
- **C)** A separate `integrationTest` suite per module for tests that need Docker, wired into `check` (so `./gradlew build` and CI run it); `./gradlew test` stays Docker-free.

### Decision
**C.** Without a daemon `integrationTest` fails with Testcontainers' own message, never skips. Spring-context tests that need no container (WebTestClient against the app, FND-04) stay in `test`.

### Consequences
- FND-03 creates the suite (a convention plugin or Gradle JVM test suites, its local choice) and the first Docker test; FND-02 adds the Testcontainers base classes to it.
- CLAUDE.md Testing and `verify-gates` list `./gradlew integrationTest`; `./gradlew build` needs Docker running.
- Kover measures both suites.
- 2026-11-03 (FND-02): the fixtures are lazily started singleton objects in `test-support`, not base classes (a test may need several services); ARCH-11 enforces Docker-only-in-integrationTest (ADR-017).

---

<a id="adr-017"></a>
## ADR-017: Test fixtures live in a test-support module, used only by integrationTest sources

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
ADR-016 promised Testcontainers fixtures for every module's `integrationTest` suite. Their consumers span families: gateway-infra, gateway-app, gateway-providers, agent-app and cli. ARCH-4 forbids `agent-*` from importing `.gateway`, and ARCH-9 checks every source set against `dependsOn`.

### Options
- **A)** `java-test-fixtures` in gateway-infra: agent-app cannot use it (ARCH-4), and gateway-infra would exist only for fixtures.
- **B)** Fixtures copied per module.
- **C)** A module `test-support` (role tool, root `.testsupport`, imports nothing of ours), plus a `testDependsOn` column in the module table that ARCH-9 honours only in `integrationTest` source sets, plus a new rule ARCH-11.
- **D)** Spring `@TestConfiguration` containers only: plain adapter tests are left out.

### Decision
**C.** ARCH-11: `org.testcontainers` is referenced only from `integrationTest` source sets and from test-support's main; `.testsupport` is referenced only from `integrationTest` source sets of modules whose `testDependsOn` lists test-support (gateway-providers, gateway-infra, gateway-app, agent-app, cli). Core modules, mock-provider, evals and architecture-tests have none. test-support has no production code and is not in the merged Kover report. Details: [FND-02](specs/FND-02-local-stack.md), ADR-FND-02-006 to -008.

### Consequences
- A module that needs containers adds `integrationTestImplementation(project(":test-support"))`.
- ADR-016's "Docker only in `src/integrationTest`" becomes a checked rule.
- Image versions shared with `deploy/compose.yaml` are kept equal by `StackContractTest`.
- 2026-11-03 (FND-02 review): ARCH-11 also forbids `org.testcontainers` in core modules' `integrationTest` sources (every core source set; core tests are plain JUnit). Because Konsist reads no `.kts`, the build adds `checkTestSupportClasspath` (in `llmgw.kotlin-jvm`, part of `check`): it fails when `:test-support` or `org.testcontainers` resolves on a `main` or `test` classpath of any module but test-support.

---

<a id="adr-018"></a>
## ADR-018: Chat domain model and JSON handling

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
GW-02, GW-03, GW-05, GW-06, RL-02, CACHE-01, GRD-02 and COST-02 all work on the same request and response. ADR-004 promises that unknown fields pass through; CLAUDE.md keeps core plain Kotlin and provider types behind the port; FND-04 left the JSON library to GW-01. The OpenAI request has 37 members and grows, while the gateway reads few of them.

### Options
Full options and reasoning in [GW-01](specs/GW-01-chat-completions.md), ADR-GW-01-001 and -002. In short:
- Model: every member typed in core; an opaque body; typed members for what the gateway reads plus neutral extras.
- JSON: Jackson 3 data binding with its Kotlin module; kotlinx.serialization; Jackson 3 tree model at the edges.

### Decision
- **Model:**
  - A domain type has typed fields only for what the gateway itself reads, and `extras: JsonValue.Object` for the rest, at each level (request, message, content part, completion, choice, assistant message, usage).
  - `JsonValue` is a sealed type in `gateway-core`; numbers keep their JSON text. Extras are the gateway API's members, which ADR-004 makes the OpenAI shape; they are not an adapter's wire type.
  - A member moves from `extras` to a typed field when an item must read it.
  - Core types that carry prompt or completion text override `toString`.
- **JSON:**
  - Jackson 3, through Boot's `JsonMapper`, tree model only, at the HTTP edge and in adapters. No `jackson-module-kotlin`, no kotlinx.serialization.
  - `gateway-core` knows neither library (ARCH-1, `checkCoreClasspath`).
  - The conversion `JsonNode` to and from `JsonValue` lives in `.gateway.providers.json`.

### Consequences
- A new OpenAI member passes through with no code change.
- An adapter for a provider that cannot take a member declares what it does with it (GW-03).
- CACHE-01's key covers `extras`. GRD-02 states what it does with text inside `extras`.
- The error envelope and its codec path are unchanged.
- Readers and writers are explicit code, unit-tested without Spring; no reflection runs on the hot path.

---

<a id="adr-019"></a>
## ADR-019: Streaming contract

- **Status:** Accepted
- **Date:** 2026-11-03

### Context
GW-03, RTE-02, RL-02, COST-02, GRD-05, CACHE-04, OBS-01 and CLI-01 all work on the same stream. ADR-015 says an error after commit becomes an SSE event with the same envelope; CLAUDE.md allows fallback only before the first byte and wants usage from the provider. Each of those items needs one answer to: what the port returns, when the status is fixed, how a failed stream ends, and how the gateway learns usage.

### Options
Full options and reasoning in [GW-02](specs/GW-02-sse-streaming.md), ADR-GW-02-001, -002, -005 and -007. In short:
- Port: `Flow<ChatChunk>` with exceptions; a suspending open returning a flow; a cold flow of sealed events.
- Chunks: forward bytes after a light read; translate to typed core with extras; both.
- Failure after commit: end with `[DONE]`; abort the connection; a clean end with no `[DONE]`.
- Usage: forward the client's choice; always request it.

### Decision
- **Port:** `ChatModelPort.stream` returns a cold `Flow<ProviderStreamEvent>`: chunks, then exactly one terminal event. An expected failure is a value at any position. Cancelling the collector cancels the provider call.
- **Commit point:** the first frame, after a 2xx and the first valid chunk, marked by `ChatStreamEvent.Started`. Before it, a failure is an ordinary error response through the one mapper; fallback and headers may change only before it.
- **Chunks:** every chunk is translated to `ChatChunk`, typed plus `extras` (ADR-018). A typed field holds only a value of the expected type, so presence and `null` survive.
- **Failure after commit:** one `data` event with the ADR-015 envelope, no `[DONE]`, and a clean end of the body. `[DONE]` means success and nothing else.
- **Usage:** the gateway always requests it upstream and shows it only to a client that asked. When the provider omits it, the estimate is flagged by `"x_gateway":{"usage_estimated":true}` on the usage chunk, since no header can follow commit.
  - 2026-11-04: the member is named `llmgw`, not `x_gateway` (user: "llmgw (Recommended)"), matching the CLI and unlikely to collide with a future OpenAI field.
- **End of stream:** `StreamSummary` is reported exactly once, for every outcome, with a `Usage` always present. A terminal event beats a late cancellation.

### Consequences
- An adapter emits the same events, whatever its provider's wire format.
- RTE-02 falls back inside the cold flow, before `Started`.
- RL-02 and COST-02 consume `StreamSummary`, including for cancelled streams.
- GRD-05 and CACHE-04 work on `Flow<ChatStreamEvent>`.
- An access log shows 200 for a stream that failed after commit; OBS-02 counts failures from `StreamSummary`.
- What OpenAI itself sends after an in-band error has no public document; a `live` call can check it.

<!--
Template for a new ADR (copy above this comment):

<a id="adr-NNN"></a>
## ADR-NNN: <title>

- **Status:** Proposed | Accepted | Superseded by ADR-MMM
- **Date:** YYYY-MM-DD

### Context
### Options
### Decision
### Consequences
-->
