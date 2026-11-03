---
id: bugs/gateway-body-limit-on-functional-route-gives-500
type: bug
title: A body over the codec limit on a functional gateway route would get 500 and an ERROR trace instead of 413
status: active
severity: low
tags: [errors, spring, webflux, gateway-app]
related: [patterns/one-error-funnel, systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslator.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ContentTooLargeIntegrationTest.kt
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/ExceptionTranslatorTest.kt
---

## Summary

`ExceptionTranslator` gives `ContentTooLarge` only for a `ServerWebInputException` caused by a `DataBufferLimitException`, or a 413 `ErrorResponse`. A functional handler's body read raises a bare `DataBufferLimitException`, so an oversized request would become a 500 with an ERROR stack trace. Latent: no gateway route reads a body yet, and FND-04 leaves `coRouter` open for GW-01.

## Symptom

Found in the second-pass review (spring-webflux 7.0.10, reactor-netty 1.3.8), on a real server with `maxInMemorySize(1024)`, a `RouterFunctions.route` handler calling `req.bodyToMono(String.class)` and a 4096-byte POST:

```
FUNCTIONAL top-level=org.springframework.core.io.buffer.DataBufferLimitException isErrorResponse=false
```

Under `toGatewayError` that exception is neither case, so it falls to `GatewayError.Internal`. Any client could produce one 500 and one ERROR trace per request.

## Root cause

Spring translates the limit on one path only. `AbstractMessageReaderArgumentResolver.handleReadError` maps `DataBufferLimitException` to `ContentTooLargeException` (bytecode). `DefaultServerRequest` maps only `UnsupportedMediaTypeException` and `DecodingException`. The rule at `ExceptionTranslator.kt:30` and its comment at `:28-29` assume every request-side limit arrives wrapped, and `ContentTooLargeIntegrationTest` covers only `ProbeController`, an annotated controller. `ExceptionTranslatorTest:82` pins the bare exception to `Internal`. `mock-provider` is unaffected: it uses `coRouter`, and `MockErrorTranslator.kt:35` matches the limit anywhere in the chain.

## Fix

Not fixed, by decision (2026-11-03): no code until GW-01 picks its route style; its roadmap Notes and the systems entry carry the rule. Either option works:

- Treat a top-level `DataBufferLimitException` as `ContentTooLarge`, keeping `Internal` for one wrapped in another exception. The systems entry already says adapters translate their own limit, so a bare one can only come from the request read. Update `ExceptionTranslatorTest:82` and FND-04 translator step 2.
- If GW-01 uses annotated controllers only, record that in its spec and add a rule or test that no functional route reads a body.

## Prevention

`ContentTooLargeIntegrationTest` needs a functional route beside the controller probe. Review rule: when a rule depends on how the framework wraps an exception, test each entry path, because controllers and functional handlers translate differently.
