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
- The pre-commit hook and `verify-gates` run `scripts/clean-room-scan.sh`.
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
