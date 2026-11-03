---
name: feature-loop
description: Work through one or more roadmap items end to end - study, plan (spec + ADRs), TDD implementation, gates, review, smoke or eval run, living docs, roadmap, commit - with a RUN-PLAN in the scratchpad that survives context compaction. Heavy work goes to subagents so the orchestrator stays lean. Use when the user says "do the next items", "work through RL-01 to RL-03", "continue the roadmap", "faz as próximas features", "executa esses ajustes em sequência", or lists several changes to make in one go.
---

# feature-loop

The orchestrator (you) stays lean: it decides, keeps the RUN-PLAN current and hands heavy work (study, implementation, review, runs) to subagents. You read their reports, decide, update the RUN-PLAN and start the next step. Invoking this skill is the user's go-ahead to use those agents and to commit each finished item locally. It is not a go-ahead to push.

## Step 0: preflight

Run the `preflight` skill. Behind the upstream: stop and sync first (new rules may have landed). It also asks the two parallel-work questions: worktree or main checkout, and is another agent working now. Postgres, Redis, the broker and the app ports are shared, and two Flyway migrations or two smoke runs against one stack corrupt each other.

## Step 1: the RUN-PLAN

Write `RUN-PLAN.md` in the session scratchpad (never in the repo). Re-read it after any compaction before doing anything else. It holds:

1. **Items**, by roadmap ID (`docs/roadmap.md`), in order, respecting `Needs`.
2. **Per item:** status (todo / doing / done / handed back), spec path, commit hash when done.
3. **Clarity:** execute, or hand back (see "Hand back"). Confirm the cut with the user at the start: what is in, what is out, what needs them, rough size.
4. **Decisions** taken and where they were recorded (ADR IDs).
5. **Hand-back log**, append-only: questions waiting for the user.

Update it at every status change. It is cheap and it is what survives a compaction.

## Per item, one at a time

1. **Study.** Launch `Explore` subagents in parallel (read-only), one per axis: current code around the change, the `docs/knowledge/systems/` entries it touches, `bugs/` and `gotchas/` for the files involved, and the public references it needs (provider API docs, OpenTelemetry GenAI conventions, Redis or Kafka semantics). Ask for `path:line`, current behaviour, what changes, risks, and whether a migration is needed. Put the essentials in the RUN-PLAN.
2. **Plan.** For an item that adds an API, a system, a schema change or spans modules, run the `gateway-architect` agent. It writes or updates `docs/specs/<ID>-<slug>.md` from `docs/specs/_template.md` with local ADRs, and `docs/DECISIONS.md` if the decision is cross-cutting. Take the decisions yourself (CLAUDE.md rule 5). A question only the user can answer goes to the hand-back log and the item is skipped; continue with the next independent item.
3. **Mark.** Roadmap row `[~]` with today's date; spec `status: in-progress` and a log entry.
4. **Implement.** A `general-purpose` subagent, TDD. Its prompt carries: the item, the spec path and its ADRs (so it does not re-decide), the mapped files, the systems entries to obey, the invariants below, and the gate order. It reports `path:line` changes, tests added, coverage numbers, how to exercise the change, and anything ambiguous.
5. **Gates.** The `verify-gates` skill. Read the evidence, not a summary of it. Red: back to step 4 with the concrete failure.
6. **Review.** The `gateway-reviewer` agent on the diff. Fix confirmed findings (steps 4-5). It files `bugs/` and `gotchas/` entries itself.
7. **Run.** Observable over HTTP (an endpoint, a header, a limit, a cache hit, a trace, a ledger row): the `gateway-tester` agent through the `gateway-smoke` skill. A threshold, classifier or routing policy changed: the `ai-eval` skill, with a before/after metrics table. A finding goes back to step 4.
8. **Docs.** The `docs-update` skill: spec, systems entry (created for a new system, linked from the CLAUDE.md table), `DECISIONS.md`, `GLOSSARY.md`, patterns and gotchas, diagrams (`diagram-update`), `docs/knowledge/INDEX.md`. A user decision is recorded in their words, with the date.
9. **Roadmap.** Row `[x]`, today's date, spec and systems entry linked in Notes. Follow-ups the work exposed become new `[ ]` rows with the next free ID in their area.
10. **Commit.** Stage explicit paths, only this item's files. `type(scope): summary`, body ending `Roadmap: <ID>`. The hooks run the clean-room scan and `docs-check`; a hook failure is fixed, never bypassed. Do not push.
11. **Update the RUN-PLAN** and continue.

## Invariants (put them in every implementation prompt)

- Subagents never commit, branch, push, or run `docker compose down -v`.
- Touch only the current item. A cross-cutting refactor becomes an ADR plus a new roadmap row, not a graft.
- Tests never call a real provider; no API keys in code, fixtures or logs.
- One Flyway migration per item at most; never edit an applied migration; never two migrations at once. A step that wants to reset a database: stop and report.
- Clean-room (CLAUDE.md rule 1): public sources only, cited in the spec.
- Core stays plain Kotlin; adapters own library types; the hot path never blocks.
- Only the agent running `gateway-smoke` or `ai-eval` starts the apps, and it stops them when done. The infra stack (compose) is left running unless it started it.

## Serialization

- **Study** (read-only): parallel is safe.
- **Implementation:** serial by default. Parallel only on disjoint modules, without a migration, and never two Gradle builds in one checkout.
- **Runs** (smoke, eval, load): always serial; they share Postgres, Redis, the broker and the ports.

## Hand back

An item that cannot run without guessing is skipped, with the objective questions that unblock it in the hand-back log. Four items done well and one question beat five half-done. Always the user's: scope not in the roadmap, licensing, paid provider calls or accounts, public naming, any `[?]` row.

## At the end

A short report in chat: a table of items with status and commit hash, items handed back with their questions, follow-up rows added to the roadmap, and what the runs left in the dev stack (Redis keys, Postgres rows). Say if cost or scope grew. Push only if the user asks.
