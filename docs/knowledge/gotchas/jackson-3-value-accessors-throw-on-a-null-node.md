---
id: gotchas/jackson-3-value-accessors-throw-on-a-null-node
type: gotcha
title: Jackson 3's booleanValue and stringValue throw on a NullNode, and Kotlin's ?. guards only a member that is absent
status: active
tags: [jackson, kotlin, json, errors]
related: [systems/provider-adapters, runs/2026-11-04-smoke-sse-streaming]
created: 2026-11-04
updated: 2026-11-04
---

## Summary

On Jackson 3.1.7, `JsonNode.booleanValue()` on a `NullNode` throws `JsonNodeException`, and so does `stringValue()` on a `NullNode` or `MissingNode`. `get("x")` returns a `NullNode` for a JSON `null`, so `node.get("x")?.booleanValue()` passes the `?.` and throws.

## Details

Found by the GW-02 smoke (finding 1): `"stream_options":{"include_usage":null}` gave a 500 with `tools.jackson.databind.exc.JsonNodeException: 'NullNode' method booleanValue() cannot convert value <null> to boolean`, at `ChatRequestReader.kt`. Reproduction: `JsonNodeFactory.instance.nullNode().booleanValue()`. A `stringValue()` on a `MissingNode`, from `at("/no/such")`, raises the same type: `'MissingNode' method stringValue() cannot convert value <missing>`.

The forms with a default do not throw: `stringValue(null)` gives the default. `canConvertToInt()` is false for a fraction, a `null`, a string and `1e99`, so `takeIf { it.canConvertToInt() }?.intValue()` is safe (`OpenAiChunkReaderTest`, `OpenAiResponseReaderTest`).

## How to handle it

Check the type before the accessor: `node.get("x")?.takeIf { it.isBoolean }?.booleanValue()`. In a reader of client or provider bytes, an accessor with no check is a 500 any client can trigger. The sweep of `gateway-app` and `gateway-providers` for GW-02 found this one case.
