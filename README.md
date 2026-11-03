# kotlin-llm-gateway

An open-source, self-hostable **LLM gateway** and **agent runtime**, in Kotlin and Spring Boot.

> **Status: build skeleton.** The Gradle multi-module build, its quality gates and the architecture rules exist (`FND-01`); code continues with `FND-02`. See the [roadmap](docs/roadmap.md).

## What it does (v1 target)

- **One OpenAI-compatible endpoint** in front of several providers: change the base URL and the key, keep your SDK.
- **Routing** by model alias, with fallback chains, retries and circuit breakers; latency-, cost- and budget-aware policies.
- **Caching**: exact (normalized request hash) and semantic (embedding similarity, with a measured false-hit rate).
- **Per-team rate limits** on requests and tokens, consistent across replicas (atomic Redis scripts, reserve then reconcile).
- **Guardrails**: PII redaction (including CPF and CNPJ), prompt-injection detection, output schema validation, on streams too.
- **Tracing and metrics** with the OpenTelemetry GenAI conventions; time to first token, cache hit rate, errors per deployment.
- **Cost per call** from a dated price catalog into an append-only ledger; team budgets and alerts.
- **Agent runtime**: versioned agent definitions, HTTP tools on allowlisted hosts, durable runs that resume after a crash, and orchestration patterns (sequential, parallel, supervisor). Agents call models through the gateway, so their traffic is limited, cached, guarded, traced and billed like anyone's.

- **`llmgw` CLI**: chat with streaming, register and run agents with a live view of each step, admin and cost reports, all over the same HTTP APIs.

## Architecture

- [Container overview](docs/diagrams/container-overview.md)
- [Request pipeline](docs/diagrams/sequence-chat-completion.md)
- [Agent run](docs/diagrams/sequence-agent-run.md)
- [Vision](docs/VISION.md) and [decisions](docs/DECISIONS.md)

Hexagonal Gradle modules: a plain-Kotlin core with ports and pure policies; adapters for providers, Redis, Postgres, the broker and OpenTelemetry; Spring Boot apps (WebFlux + coroutines) that wire them.

## Running locally

Arrives with `FND-02` (local stack) and `FND-03` (mock provider). No provider account is needed to run the tests or the demo.

## How this repository is built

Development is done with AI coding agents under explicit, versioned rules:

- [`CLAUDE.md`](CLAUDE.md): the rules that apply everywhere, and each system's "never break" contract.
- [`.claude/skills/`](.claude/skills/): the workflow. `feature-loop` takes roadmap items end to end (study, spec with ADRs, TDD, gates, review, smoke or eval run, docs, commit); `verify-gates`, `gateway-smoke` and `ai-eval` are its checks.
- [`.claude/agents/`](.claude/agents/): an architect that writes specs, a reviewer that files bugs and gotchas, a tester that writes run reports.
- [`docs/`](docs/): living documentation (roadmap, specs, ADRs, glossary, diagrams) and a [knowledge base](docs/knowledge/README.md) that agents read before acting and write after learning.
- Clean-room: designed only from public sources ([ADR-001](docs/DECISIONS.md#adr-001)).

## License

[MIT](LICENSE)
