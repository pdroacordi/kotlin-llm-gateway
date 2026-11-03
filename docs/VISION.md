# Vision

## In one line

A self-hostable LLM gateway and agent runtime: every team gets one OpenAI-compatible endpoint with routing, caching, rate limits, guardrails, tracing and per-call cost, and can register agents that run on top of it under the same controls.

## Problem

When several teams in one organization adopt LLMs, each wires providers directly. Provider keys spread across services. Nobody sees cost per team or per feature. One noisy team exhausts the shared provider quota. The same prompt is paid for again and again. An outage at one provider takes features down. Personal data leaks into prompts and logs. An agent's thirty model calls cannot be traced back to the user action that started them.

## Thesis

Put one control point on the request path. Because every call passes through it, the gateway is the cheapest place to authenticate, limit, cache, guard, measure, bill and fail over.

Agents are the heaviest LLM users, so the agent runtime is a **client of the gateway**, not a parallel stack. Agent traffic inherits every control without extra code, and a run's trace and cost roll up from the calls it made.

## Pillars

1. **Drop-in.** OpenAI-compatible API (ADR-004). Changing the base URL and the key is the whole migration for a client.
2. **Low overhead.** The gateway adds little latency on its own: target p99 of at most 15 ms of overhead (provider time excluded) on the uncached path without model-based guardrails. OPS-01 measures it and confirms or revises the number.
3. **Fair.** Per-team limits on requests, tokens and spend, enforced consistently across replicas.
4. **Accountable.** Every call has a team, a trace and a cost in an append-only ledger.
5. **Safe by default.** PII redaction and prompt-injection checks configurable per team; prompt and completion text never logged unless a team opts in.
6. **Resilient.** Fallback across providers and models, circuit breakers, and a defined behaviour when Redis, Postgres or the broker is down.
7. **Agents as data.** Agent definitions are versioned; runs are durable and resumable; orchestration patterns (sequential, parallel, supervisor) are declared, not coded.

## Users

- **Platform team:** operates the gateway; manages teams, keys, routes, prices, budgets and policies through the admin API; watches Grafana.
- **Product teams:** call the gateway with a team key; see their usage and cost.
- **Agent authors:** register agents and tools, start runs, inspect steps, traces and cost.

## Non-goals (v1)

- Training, fine-tuning or hosting models.
- A web UI beyond Grafana dashboards and the admin API (OPS-05 is `[?]`).
- A prompt-management product or playground.
- Multi-region active-active deployment.

## Done for v1

One command brings the stack up. Two teams call the gateway: one hits its rate limit while the other is unaffected; repeated and paraphrased questions are served from the exact and semantic caches; a simulated provider outage fails over to another model; a prompt with a CPF reaches the provider redacted; an agent workflow with tools runs, survives a restart of the runtime, and finishes; every call is visible in Jaeger and every team's cost in Grafana. A k6 run shows the overhead budget is met.

## Built clean-room

The project is designed from public sources only (ADR-001): papers, open-source gateways, vendor documentation. Specs cite them.
