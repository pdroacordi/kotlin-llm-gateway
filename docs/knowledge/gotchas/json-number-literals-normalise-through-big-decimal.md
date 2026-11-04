---
id: gotchas/json-number-literals-normalise-through-big-decimal
type: gotcha
title: A JSON number keeps its value and precision through the gateway, not its literal, so 1e2 reaches the wire as 1E+2 on both adapters
status: active
tags: [json, jackson, openai-compat, anthropic]
related: [systems/provider-adapters, patterns/typed-core-with-json-extras, runs/2026-11-04-smoke-anthropic-adapter]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
---

## Summary

`JsonValue.Number` holds text, but a float goes through `BigDecimal` on the way in and on the way out, so an exponent form does not survive. Seen on Jackson 3.1.7 and JDK 25.

## Details

Journal bodies, GW-03 smoke finding 1, the same on `mock-claude` and `mock-chat`:

| Sent | Upstream |
|---|---|
| `0.20`, `1.10`, `0.1000000000000000055511151231257827`, a 30-digit integer | unchanged |
| `1e-1` | `0.1` |
| `2.50E-1` | `0.250` |
| `1E0` | `1` |
| `1e2` | `1E+2` |
| `1e-3` in a tool argument | `0.001` |
| `1e999` in a tool argument | `1E+999` |

The value is equal and no precision is lost. `exactTreeReader()` reads floats as `BigDecimal`, and `toJsonNode` writes `BigDecimal(literal)` (`JsonTrees.kt`), so the text is `BigDecimal`'s, not the client's.

## How to handle it

Assert the value, not the spelling, of an exponent-form number. A provider that wants an integer could read `1E+2` differently from `100`; none has been seen to. GW-03 section 4 states the rule: same value and precision, the literal may normalise.
