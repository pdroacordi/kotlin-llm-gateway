---
id: gotchas/mock-scripted-tool-input-normalises-number-literals
type: gotcha
title: The mock provider normalises a scripted tool call's input, so 1.10 comes out as 1.1 and a reply's number literal cannot be tested through it
status: active
tags: [mock-provider, testing, json, tools]
related: [systems/mock-provider, gotchas/json-number-literals-normalise-through-big-decimal, runs/2026-11-04-smoke-anthropic-adapter]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScriptedContentJson.kt
---

## Summary

`toolCalls[].input` in a scenario goes through the mock's plain mapper at `PUT`, so `{"n":1.10}` is served as `{"n":1.1}`, seen directly on `/v1/messages` (GW-03 smoke). ADR-GW-03-019's "kept as compact text" holds for the text it writes, not the text it was sent.

## Details

`ScriptedContentJson.toolCall` keeps `mapper.writeValueAsString(input)` of the parsed tree. The mock imports nothing of ours (rule 6 of its contract), so it has no `exactTreeReader`, and a float in its tree is not kept as written.

The gateway is not at fault: through a stdlib stub, `1.10` and `12345678901234567890.5` came back byte-equal on the reply.

## How to handle it

Test a reply's number literal with a raw-socket provider or a stub, as `RawAnthropicStreamIntegrationTest` does for wire shapes the mock cannot produce. Through the mock, assert the value. The request direction is unaffected: the journal keeps what the gateway sent.
