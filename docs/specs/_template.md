---
id: <ID>
title: <Item name>
status: not-started   # not-started | in-progress | done | blocked | skipped
needs: []             # roadmap IDs that must be [x] first
owner: —
updated: YYYY-MM-DD
references: []        # public sources the design relies on (URLs, papers, OSS projects): ADR-001
---

<!--
Spec template. Copy to docs/specs/<ID>-<slug>.md (ID = the roadmap row) when an item adds an API,
a system, a schema change or spans modules. Keep every section; write "n/a" and why when one does not apply.
-->

# <ID>: <Item name>

## 1. Context and goal

Two to four short paragraphs: what problem this solves for which user (VISION users), which pillar it serves, why now.

## 2. Scope

### In
- …

### Out
- …

## 3. Acceptance criteria

Checkable. All ticked before the row becomes `[x]`.

- [ ] …
- [ ] `verify-gates` green (coverage: core ≥ 80%, overall ≥ 70%)
- [ ] Smoke scenarios in section 8 pass (`gateway-smoke`), report in `docs/knowledge/runs/`
- [ ] Eval meets its selection rule (`ai-eval`), if section 8 lists a suite
- [ ] Living docs updated (`docs-update`), including the systems entry

## 4. Contract

HTTP paths, request and response shapes, headers (`x-gateway-*`), status codes, errors in the OpenAI envelope. What stays OpenAI-compatible (ADR-004) and what is an extension.

## 5. Design

### Modules and ports
Which module holds each piece; port interfaces with Kotlin signatures; which adapter owns which library.

### Data
Flyway tables and indexes; Redis keys (layout, TTL); broker topics (key, schema, retention). The source of truth for each fact.

### Flow
A Mermaid sequence here, or a link to the updated file in `docs/diagrams/`.

### Failure model
Timeouts, retries, cancellation, idempotency keys. For each dependency (provider, Redis, Postgres, broker) slow or down: the behaviour, fail-open or fail-closed, and why.

### Observability
Spans and GenAI attributes, metrics with units, what is never recorded.

### Configuration
Properties with defaults and units.

## 6. Reuse

Existing code, patterns (`docs/knowledge/patterns/`) and ADRs this item builds on.

## 7. Execution plan

Steps of about half a day each; tick as they land.

- [ ] **Step 1:** …
- [ ] **Step 2:** …

## 8. Tests

- **Core unit (TDD):** …
- **Integration (Testcontainers, mock provider):** …
- **Smoke scenarios (`gateway-smoke`):** …
- **Eval (`ai-eval`):** suite, selection rule, tolerance, or "n/a".

## 9. Success metrics

Numbers that show it works: added latency, hit rate, false-hit rate, error rate, cost.

## 10. ADRs (local)

### ADR-<ID>-001: <title>
- **Context:** …
- **Options considered:**
  - A) …
  - B) …
- **Decision:** …
- **Consequences:** …
- **Date:** YYYY-MM-DD

## 11. Log

Append-only, newest at the bottom. Every session on this item ends with an entry.

### YYYY-MM-DD: <short title>
- **Who:** …
- **Done:** …
- **Decisions:** … (or "none")
- **Next:** …
