---
id: patterns/typed-core-with-json-extras
type: pattern
title: Typed fields for what the gateway reads, neutral JSON extras for the rest, so an evolving wire shape passes through a plain-Kotlin core
status: active
tags: [openai-compat, domain-model, json, hexagonal, pass-through]
related: [patterns/upstream-bytes-then-pure-classification, patterns/member-policy-table-for-a-foreign-provider, systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-05
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/json/JsonValue.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatRequest.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
---

## Summary

A wire format the gateway proxies has far more members than the gateway reads, and it changes without notice. Each domain type keeps typed fields for the members the gateway acts on, and an `extras: JsonValue.Object` that carries every other member verbatim, so unknown members survive and core still has a real domain.

## Details

Canonical use: GW-01 spec, section 5, and [ADR-018](../../DECISIONS.md#adr-018).

- **`JsonValue` in core.** A small sealed type: `Null`, `Bool`, `Number(literal)`, `Text`, `Array`, `Object` (insertion-ordered). No JSON library in core.
- **Numbers stay text.** `Number` holds the JSON literal, so `9007199254740993` is not rounded on the way through.
- **Extras at every level that has unknown members,** not only the top: request, message, content part, completion, choice, assistant message, usage. A top-level bag loses `tool_calls` inside a message.
- **Reserved keys.** The type's `init` rejects an `extras` key that is also a typed field, so an adapter cannot write a member twice.
- **Readers and writers at the edges.** Reader: copy known members to fields, the rest to `extras`, keeping order. Writer: typed members first, then `extras`. That reproduces the provider's member order for the same shape.
- **Promotion.** When an item must read a member, it becomes a typed field and leaves `extras`. The change is additive and local.

Add a member to the typed part only when the gateway reads it. "It might be useful" stays in `extras`.

## Why

- Modelling every member makes each provider release a core change and silently drops what is not modelled yet (ADR-004 forbids that). An opaque body leaves nothing for a second adapter, a redactor or a cache key to work with.
- A neutral type keeps "only the adapter sees a library's types" true: the port names only core types.
- Public basis: the OpenAI reference lists 37 request members (https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create). The tolerant-reader idea is general practice; the split into typed fields and an extras bag is this project's design, with no single public source.

## Gotchas / pitfalls

- A data class's default `toString` prints everything. Types that carry prompt or completion text override it, or one `log.debug("{}", request)` breaks the no-prompt rule.
- `extras` is outside any typed scan: a redactor (GRD-02) or a cache key (CACHE-01) must say what it does with it.
- An adapter for a provider with a different shape must not forward `extras` blindly: it declares which members it maps, and what happens to the rest.
- Absent and `null` differ. A reader that drops `null` members changes a body (`"usage": null`, `"refusal": null`).

## Revision (2026-11-04)

Promotion happens when `gateway-core` or the app must read a member. An adapter that translates a member reads it from `extras`, which hold the gateway API's members in the OpenAI shape, and states what it does with it ([patterns/member-policy-table-for-a-foreign-provider](member-policy-table-for-a-foreign-provider.md)). Reason: a promoted member has three states (typed, `null`, wrong type left in `extras`), and every other adapter must re-emit it. Decided in GW-03, ADR-GW-03-017; [ADR-018](../../DECISIONS.md#adr-018) carries the same Revision.

## Revision (2026-11-05)

Core may measure a member in `extras` without promoting it, if it never branches on the value: `UsageEstimator` counts the characters of `tool_calls`. Promotion stays the rule for a member core or the app acts on. Decided in GW-06, ADR-GW-06-003; [ADR-018](../../DECISIONS.md#adr-018) carries the same Revision.
