---
id: gotchas/spring-httpstatus-deprecated-aliases-never-resolved
type: gotcha
title: Spring 7 HttpStatus has two constants for 413 and 422; resolve() and exceptions return the new one, so == on the deprecated one is always false
status: active
tags: [spring, http, errors]
related: [systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslator.kt
---

## Summary

Spring Framework 7.0.10's `HttpStatus` keeps the deprecated `PAYLOAD_TOO_LARGE` beside `CONTENT_TOO_LARGE` (413), and `UNPROCESSABLE_ENTITY` beside `UNPROCESSABLE_CONTENT` (422). The lookups return only the new constants.

## Details

Observed 2026-11-03 against `spring-web-7.0.10.jar`:

- `HttpStatus.resolve(413)`, `HttpStatus.valueOf(413)` and `HttpStatusCode.valueOf(413)` all return `CONTENT_TOO_LARGE`.
- `ContentTooLargeException(...).statusCode` is `CONTENT_TOO_LARGE`, and `== HttpStatus.PAYLOAD_TOO_LARGE` is `false`.
- `HttpStatus.resolve(422)` is `UNPROCESSABLE_CONTENT`.

A `when` branch on `HttpStatus.PAYLOAD_TOO_LARGE` or `UNPROCESSABLE_ENTITY` compiles, with only a deprecation warning, and never matches.

## How to handle it

Compare status integers (`statusCode.value()`), as `ExceptionTranslator.kt:45-82` (`fromStatus`, with the comment at `:43-44`) does. Do not branch on the deprecated constants.
