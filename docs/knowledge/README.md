# Engineering knowledge base

This directory is the project's engineering memory: how each system works now and what it must not break, the reusable patterns it is built from, what broke, what the libraries surprised us with, and how the last runs went. It exists so that an agent or a person opening the project cold retrieves exactly the prior knowledge the task needs instead of re-discovering it, or re-making a mistake already paid for.

It is not the place for decisions: cross-cutting ADRs are in [`../DECISIONS.md`](../DECISIONS.md) and item-local ADRs in the item's spec (`../specs/`). It is not the product description either: that is [`../VISION.md`](../VISION.md).

It is written **for retrieval**, not for reading front to back. Every file is one self-contained unit: one system, one pattern, one bug, one gotcha, one run.

## The self-improvement rule

Every agent that touches the codebase **reads before it acts and writes after it learns something new.**

- `gateway-architect` greps `patterns/` and `gotchas/` before planning, and adds a `patterns/` entry when a plan establishes a genuinely new reusable pattern.
- `gateway-reviewer` greps `bugs/` and `gotchas/` before reviewing, adds a `bugs/` entry for every confirmed bug, and a `gotchas/` entry for every non-obvious library or provider behaviour it finds.
- `gateway-tester` always adds a `runs/` entry; it is its whole output. `ai-eval` runs also land in `runs/`.
- Whoever changes a system updates its `systems/` entry in the same change; whoever builds a new system creates it and links it from the CLAUDE.md systems table.

Do not skip the write because the task felt small. A gotcha that cost 20 minutes costs the next session another 20 unless it is written here.

## File format

Every entry is one Markdown file with this frontmatter:

```yaml
---
id: <category>/<slug>        # equals the path without .md, e.g. gotchas/webflux-sse-buffered-by-proxy
type: system | pattern | bug | gotcha | run
title: One line, human-readable
status: active | superseded | deprecated
tags: [lowercase, kebab-case]
related: [other/entry-ids]   # link liberally; a dangling id marks a gap
created: YYYY-MM-DD
updated: YYYY-MM-DD
source_files:                # the files the entry is about, so it can be invalidated when they change
  - gateway-core/src/main/kotlin/...
---
```

Each category has a `_template.md` with its body sections. Bugs add `severity: low | medium | high | critical`; runs add `kind`, `build`, `area_tested` and `verdict`.

**Revising an entry:** `systems/` entries are rewritten to stay current (their history lives in specs, bugs and git). Every other category is revised in place with a dated `## Revision (YYYY-MM-DD)` section; a new file that supersedes the old one (`status: superseded`, `related` pointing forward) is the exception, for when the original context no longer holds.

## Naming and structure

- One concept per file. Tempted to write "and also": make a second file and link it.
- Filenames are lowercase kebab-case slugs that say the content (`redis-script-keys-must-be-passed-in-keys.md`, not `bug-3.md`). Runs are `YYYY-MM-DD-<slug>.md`.
- Keep files short. Past about 150 lines it is probably two entries.
- Never delete an entry because it is outdated: mark it `superseded` and link forward.
- Update [`INDEX.md`](INDEX.md) in the same edit that adds or changes an entry. `scripts/docs-check.sh` fails on an unindexed entry or an id that does not match its path.
- Concrete over vague: `path:line`, numbers with units, the command that reproduces.
- Clean-room (ADR-001): nothing here comes from an employer, including "this is how it worked at work".

## Directories

- `systems/`: the current contract of each system: how it works and the rules a change must not break.
- `patterns/`: reusable structural patterns ("how this kind of thing is built here").
- `bugs/`: confirmed bugs: symptom, root cause, fix, prevention.
- `gotchas/`: library, framework or provider behaviour that surprised someone, independent of one bug.
- `runs/`: dated smoke, load and eval reports.
