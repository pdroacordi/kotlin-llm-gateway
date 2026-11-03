---
id: systems/<slug>
type: system
title: <System>: <one line>
status: active
tags: []
related: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
source_files:
  - gateway-core/src/main/kotlin/...
---

## Summary

One or two sentences: what the system does, from a caller's point of view.

## Contract (never break)

Numbered rules a change must not break. Each names the test that guards it. Rule 1 is the one in the CLAUDE.md systems table.

1. …

## How it works

The pieces: modules, ports, adapters, data (tables, Redis keys, topics), and how a request flows through them. `path:line` references.

## Failure behaviour

What happens when each dependency is slow or down, and whether the system fails open or closed.

## Configuration

Properties, defaults, units.

## Decisions

Links to the ADRs (spec-local and cross-cutting) that shaped it. User decisions quoted, with the date.

## Gotchas

Traps a change to this system could fall into; link `gotchas/` and `bugs/` entries.
