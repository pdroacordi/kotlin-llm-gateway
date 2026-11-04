---
id: gotchas/jackson-big-decimal-float-overflow-raises-bare-number-format-exception
type: gotcha
title: Jackson 3.1.7 with big-decimal floats raises a bare NumberFormatException quoting the literal, not a JacksonException
status: active
tags: [jackson, json, errors, logging]
related: [systems/provider-adapters, patterns/typed-core-with-json-extras]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
---

## Summary

With `JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS`, `readTree` on a float past `BigDecimal`'s range throws `java.lang.NumberFormatException`, which is not a `JacksonException`. Its message quotes the number, so a catch of `JacksonException` alone gives a client a 500 and puts their value in an ERROR log.

## Details

Jackson 3.1.7, probed through `exactTreeReader`:

- `{"a":1e99999999999}`, `1e-99999999999` and `0e99999999999`: `NumberFormatException: Value "1e99999999999" cannot be deserialized as java.math.BigDecimal, reason: value exceeds limits`.
- `1e999999999` and `1.5e2147483648` read fine and stay in exponent form. Nothing expands them: `DecimalNode.asString()` is `BigDecimal.toString()`, `canConvertToInt` answers in under 2 ms, and the generator writes `1E+999999999`.

Every sibling is a `JacksonException`, so one catch covers it: `StreamConstraintsException` (number over 1000 digits, depth over 500, name over 50 000 characters), `StreamReadException` (bad UTF-8, NaN, control characters), `JacksonIOException` (bad UTF-32), `MismatchedInputException` (trailing tokens). `StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION` is off, so their locations say `REDACTED`, but a token can still be quoted, as in `Unrecognized token 'x'`. 20 000 fuzzed numbers with exponents at the `int` limits raised nothing past the read, and all kept their value through `toJsonNode` and back.

## How to handle it

Read a body only through `readTreeOrNull` (`JsonTrees.kt:28`), which catches both and keeps no message. Never log either exception's message.
