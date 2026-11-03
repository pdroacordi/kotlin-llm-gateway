---
id: runs/<YYYY-MM-DD-slug>
type: run
kind: smoke | load | eval
title: <one line: what was run against what>
status: active
build: <git short hash>
area_tested: <items or systems, e.g. "RL-01 rate limiting, two teams">
verdict: pass | pass-with-findings | fail
tags: []
related: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
source_files: []
---

## What was run

Scenarios or eval suite, stack (services and versions), mock-provider settings (latency, error rate), seeds, dataset versions, load profile.

## Results

| Scenario / metric | Expected | Observed | Result |
|---|---|---|---|
| | | | |

## Findings

Each with its evidence: request (secrets redacted), status, headers or body excerpt, and the Redis key, span or row that proves it.

## What this run cannot show

Real provider latency and errors, multi-replica effects with one replica, other load profiles, anything else outside the run's reach.
