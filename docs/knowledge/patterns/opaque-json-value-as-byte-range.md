---
id: patterns/opaque-json-value-as-byte-range
type: pattern
title: A large JSON value the gateway only carries is copied as its byte range from a streaming parse, never built as a tree
status: active
tags: [json, jackson, streaming, memory, pass-through, openai-compat]
related: [patterns/typed-core-with-json-extras, patterns/upstream-bytes-then-pure-classification, gotchas/json-number-literals-normalise-through-big-decimal, gotchas/jackson-big-decimal-float-overflow-raises-bare-number-format-exception, systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
---

## Summary

A tree costs objects per element and rewrites number literals, which is wrong for a value of millions of numbers the gateway only passes on. One streaming parse finds each member's byte range and copies the opaque values raw; small members go through the normal tree read on their slice.

## Details

Canonical use: GW-05 spec, section 5, "The reply scan", and ADR-GW-05-005 (planned: `JsonMembers`, `OpenAiEmbeddingReader`, `RawJson`).

- **Start:** at the value's token, `currentTokenLocation().getByteOffset()`.
- **Advance:** `skipChildren()` for an array or object, `finishToken()` for a string. A string token is incomplete until then, and `currentLocation()` would stop after the opening quote.
- **End:** `currentLocation().getByteOffset()`, just past the value.
- **Core type:** `RawJson(bytes)`, plain Kotlin, equal by content, `toString` prints the size. Core knows no parser.
- **Output:** append the bytes between constants. `JsonGenerator.writeRawValue` takes text, so it widens every byte.
- **Small members:** `readTreeOrNull` on the slice, so extras keep their usual path, and one catch covers both.
- **Off the event loop:** a body worth scanning this way is worth moving off it (ADR-GW-05-010).

Use this only for a value with no reader in the gateway and a size that matters. Everything else is `extras`.

## Why

- Estimated, not profiled: 6.3 million floats through a `BigDecimal` tree and `JsonValue` is over 1 GiB by estimate; as bytes it is the reply's size.
- The value stays byte-identical, escapes and exponents included, which a tree does not promise.
- Well-formedness is still checked: `skipChildren` lexes every token without converting a number.
- Copies beat slices of the body: peak heap is the same, and a kept value does not pin the whole body.
- Public basis: `JsonParser` and `UTF8StreamJsonParser` in jackson-core 3.x (https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/json/UTF8StreamJsonParser.java). Using the offsets to carry raw values is this project's design, with no single public source.

## Gotchas / pitfalls

- `getByteOffset()` is -1 on a source Jackson does not read as bytes, such as a body detected as UTF-16. Treat that as unreadable, or the copy throws.
- Binding a subtree on a parser from a reader with `FAIL_ON_TRAILING_TOKENS` may refuse the tokens that follow it. Slicing avoids the question.
- Check for a trailing token after the root yourself: a scan that stops at `END_OBJECT` accepts `{}garbage`.
- A parser's exception quotes the input. Catch `JacksonException` and `NumberFormatException` and keep no message.
- The first use of a parser needs a warm-up like a tree's: run one sample scan at startup.
