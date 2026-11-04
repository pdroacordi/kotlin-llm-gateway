---
id: patterns/member-policy-table-for-a-foreign-provider
type: pattern
title: An adapter for a provider of another shape holds one rule per request member as data, so nothing is dropped silently
status: active
tags: [adapters, anthropic, openai-compat, pass-through, data-over-branches]
related: [patterns/typed-core-with-json-extras, patterns/one-error-funnel, systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/ChatRequest.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/error/GatewayError.kt
---

## Summary

`extras` passes through an adapter whose provider speaks the gateway's shape. An adapter for any other shape cannot forward it, and dropping what it does not know changes the meaning of a request with no signal. So it holds a table with one rule per member, checks the request against it before any I/O, and refuses what it cannot honour with a 400 that names the member.

## Details

Canonical use: GW-03 spec, section 4, `AnthropicMemberPolicy`; [ADR-020](../../DECISIONS.md#adr-020).

- **Rules.** `Translated` (the writer owns it), `Forward` (same name, same value), `Ignore` (cannot change the output), `IgnoreWhen(neutral values, code)` (a default counts as absent, otherwise reject), `Reject(code)`.
- **Default for the unlisted is Reject.** A new member of the API shape is a visible 400 until someone gives it a row.
- **`null` counts as absent** for every member.
- **Pure and before I/O.** The writer returns a body or a refusal. A refusal is a sealed value, so it is a status on both the non-stream and stream paths, with no socket touched.
- **`param` is checked** against the character rule: an unknown member's name comes from the client.
- **The test proves coverage.** A checked-in list of the API's members must each have a rule, so the table cannot lag silently, and each row has a case.

## Why

- A silent drop gives a client an answer it believes has a property it lacks, such as a fixed `seed`, and the failure appears far from its cause. Forwarding unknowns relies on the provider rejecting them, and a same-named member can mean something else.
- A table beats a `when` per member: data over branches, and coverage becomes testable.
- Public basis: LiteLLM raises on an unsupported parameter unless `drop_params` is set (https://docs.litellm.ai/docs/completion/drop_params); Portkey's `transformUsingProviderConfig` loops over the provider's config, so an unmapped member is dropped (https://github.com/Portkey-AI/gateway/blob/main/src/services/transformToProviderRequest.ts). The table form and the default-counts-as-absent rule are this project's design, with no single public source.

## Gotchas / pitfalls

- Clients send defaults they never chose (`n: 1`, `logprobs: false`, `frequency_penalty: 0`). Rejecting on presence fails them over values that change nothing; compare numbers numerically, so `0.0` passes.
- The table states what the provider's API can express, not what each of its models accepts. A rule per model is a provider's model list restated in code: forward, and relay its 400.
- A member at a nested level (message, content part, tool) needs a rule too; the top level is not the whole request.
- Do not reuse a "provider rejected" failure: nothing was sent, and a fallback needs to know that.
