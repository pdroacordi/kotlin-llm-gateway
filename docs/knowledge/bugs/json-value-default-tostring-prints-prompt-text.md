---
id: bugs/json-value-default-tostring-prints-prompt-text
type: bug
title: JsonValue keeps the data class toString, so logging any extras prints prompt text
status: active
severity: low
tags: [core, logging, secrets, json]
related: [patterns/typed-core-with-json-extras, systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/json/JsonValue.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/Usage.kt
---

## Summary

ADR-018 says core types that carry prompt or completion text override `toString`. `JsonValue.Text`, `Array` and `Object` do not, and they carry tool definitions, `tool_calls` arguments and the whole of an image or audio part. `log.debug("{}", request.extras)` or `{}` of `ContentPart.Opaque.part` prints them. No line logs one today.

## Symptom

Found in the GW-01 review. `Usage`, a data class, prints its `extras` the same way, so `ProviderResult.Completed.toString()` ends in `extras=Object(members={...})`. `ChatRequestTest`'s canary list holds no `JsonValue` and no `Usage`.

## Root cause

The spec's list of overrides (section 5) names the carriers of typed text and misses the type that holds all the rest (`JsonValue.kt:19-40`).

## Fix

`JsonValue.Text`, `Array` and `Object` print a length or a count (`JsonValue.kt:22`, `:28`, `:35`), and `Usage` prints its counts and flag (`Usage.kt:29`).

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`ChatRequestTest.textCarriers` covers `JsonValue.Text`, `Array` and `Object`, `Usage`, `ChatCall`, `ProviderResult.Completed` and `ChatOutcome.Answered`; six of them were red first.
