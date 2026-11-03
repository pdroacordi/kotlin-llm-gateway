---
id: bugs/<slug>
type: bug
title: <one line, symptom first>
status: active
severity: low | medium | high | critical
tags: []
related: []
created: YYYY-MM-DD
updated: YYYY-MM-DD
source_files:
  - path/to/file.kt
---

## Summary

One or two sentences: what breaks and under what condition.

## Symptom

What was observed: the request, the response or log line, how to reproduce, and where it was found (review, smoke run, test).

## Root cause

The mechanism, with `path:line`. Traced, not "seems to be".

## Fix

What changed, where (`path:line`, and the commit hash once committed). "Open" if not fixed yet.

## Prevention

What would have caught it earlier (a test, a Konsist rule, a rule scan in `verify-gates`, a review checklist item), and whether it was added.
