---
name: skill-library
description: Router into the ECC skill and agent catalogue for kotlin-llm-gateway (Kotlin, Spring Boot, Redis, Postgres, Kafka, OpenTelemetry, LLM APIs). Use when a task needs something outside the project's own skills - Kotlin or Spring patterns, Redis or Postgres specifics, migrations, API design, security review, LLM cost or eval patterns, Docker or Kubernetes - to find the right reference instead of guessing.
---

# Skill library

The project's own skills (`preflight`, `feature-loop`, `verify-gates`, `gateway-smoke`, `ai-eval`, `docs-update`, `diagram-update`) and agents (`gateway-architect`, `gateway-reviewer`, `gateway-tester`) cover the daily loop. This router points at the rest on demand. Project rules (CLAUDE.md) win over any generic skill.

## On stack, by topic

| Need | Skill or agent |
|---|---|
| Kotlin idioms, coroutines, Flow | `ecc:kotlin-patterns`, `ecc:kotlin-coroutines-flows` |
| Kotlin tests (JUnit 5, MockK, coroutines-test) | `ecc:kotlin-testing`, `ecc:tdd-workflow` |
| Spring Boot structure, security, TDD, verification | `ecc:springboot-patterns`, `ecc:springboot-security`, `ecc:springboot-tdd`, `ecc:springboot-verification` |
| Kotlin build or Gradle failure | `ecc:kotlin-build-resolver` agent, `ecc:gradle-build` |
| Kotlin review (second opinion beside `gateway-reviewer`) | `ecc:kotlin-reviewer` agent |
| Redis data structures, Lua, pipelines | `ecc:redis-patterns` |
| Postgres schema, indexes, pgvector | `ecc:postgres-patterns`, `ecc:database-reviewer` agent |
| Flyway migrations, zero-downtime changes | `ecc:database-migrations` |
| REST/OpenAPI design, error envelopes | `ecc:api-design`, `ecc:contract-first` |
| Hexagonal boundaries | `ecc:hexagonal-architecture` |
| Error handling and silent failures | `ecc:error-handling`, `ecc:silent-failure-hunter` agent |
| Security review (auth, SSRF in agent tools, secrets) | `ecc:security-review`, `ecc:security-reviewer` agent |
| LLM cost, model routing, caching patterns | `ecc:cost-aware-llm-pipeline`, `ecc:content-hash-cache-pattern` |
| Evals, regression tests for AI behaviour | `ecc:eval-harness`, `ecc:ai-regression-testing`, `ecc:agent-eval` |
| Agent harness and orchestration design | `ecc:agent-harness-construction`, `ecc:team-agent-orchestration`, `ecc:autonomous-agent-harness` |
| MCP clients and servers (AGT-07) | `ecc:mcp-server-patterns` |
| Latency and performance work | `ecc:latency-critical-systems`, `ecc:benchmark`, `ecc:performance-optimizer` agent |
| Docker, compose, Kubernetes, Helm | `ecc:docker-patterns`, `ecc:kubernetes-patterns`, `ecc:deployment-patterns` |
| ADR writing | `ecc:architecture-decision-records` |
| Current library or API docs | `ecc:docs-lookup` agent, `ecc:documentation-lookup` |
| Claude API specifics (Anthropic adapter, GW-03) | `claude-api` |

## Not here

Do not add ECC's generic `architect`, `code-architect`, `planner` or `code-reviewer` agents on top of the project agents; they duplicate them without the project's rules. Frontend, mobile, game and other-language skills are off stack.
