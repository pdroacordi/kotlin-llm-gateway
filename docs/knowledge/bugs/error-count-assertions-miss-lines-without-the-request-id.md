---
id: bugs/error-count-assertions-miss-lines-without-the-request-id
type: bug
title: Tests named "one ERROR line" or "no ERROR" count only lines that carry the request ID, so a second line without it passes
status: active
severity: low
tags: [testing, logging, flaky-test]
related: [gotchas/output-capture-all-includes-the-class-level-capture, bugs/committed-response-error-logged-twice-with-query-string]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ErrorEnvelopeIntegrationTest.kt
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/StreamDisconnectIntegrationTest.kt
---

## Summary

Commit 5230d89 scoped two ERROR counts by adding `"[$requestId]" in it` to the filter. Those tests used to fail on any second ERROR line; now they cannot see one that has no ID, which is the shape of the line they were written to catch.

## Symptom

Read in the second-pass review, not run. The gotcha that commit added states the limit itself: "An ID filter on a line without the ID always passes."

- `ErrorEnvelopeIntegrationTest.kt:128`, `a bug gives 500 with the request ID, no exception message, and one ERROR line`.
- `ErrorEnvelopeIntegrationTest.kt:171`, `an error after commit gives one ERROR line, with the request ID and no query string`. [The bug it guards](committed-response-error-logged-twice-with-query-string.md) was a second ERROR line with no request ID. `assertFalse(QUERY in output.all)` still catches that bug's query string, so only a second line with neither is missed.
- `StreamDisconnectIntegrationTest.kt:109`, `closing as the last frames arrive gives exactly one end line per request, and no ERROR`, and its acceptance line "no ERROR line". This one predates 5230d89. Reactor Netty's `Error finishing response` has no ID, so it passes unseen.

## Root cause

An ID filter answers "how many lines did this request log with MDC set", not "how many ERROR lines were logged". Both are right for a line known to carry the ID. An absence or a count across all loggers needs the offset.

## Fix

`ErrorEnvelopeIntegrationTest.kt:46` and `:52` take a mark in `@BeforeEach` and count every column-0 ERROR line after it, at `:147` and `:188`; `StreamDisconnectIntegrationTest.kt:25` does the same for its absence. The ID filter stays on the positive check. Commit: the second-review fix commit.

Proof: a stray ERROR line with no ID printed inside each of the three tests left them green with the old filter and turned them red with the count; without it, green.

## Prevention

The gotcha's rule, as a review item: an ID filter never goes on an absence or an "exactly one" of ERROR lines.
