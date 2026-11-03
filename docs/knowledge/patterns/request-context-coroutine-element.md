---
id: patterns/request-context-coroutine-element
type: pattern
title: Per-request context as a coroutine context element, installed by the first CoWebFilter and mirrored into MDC
status: active
tags: [webflux, coroutines, logging, mdc, request-id]
related: [patterns/one-error-funnel]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContext.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextWebFilter.kt
---

## Summary

Request-scoped values (the request ID, the client's correlation ID, later the team) must reach `suspend` handlers, log lines and the exception handler without threading the exchange through every call. Three places hold them, all set by one `CoWebFilter`:
- a `CoroutineContext` element;
- an SLF4J MDC copy, through `MDCContext`;
- an exchange attribute.

## Details

- The filter extends `CoWebFilter` and is ordered `HIGHEST_PRECEDENCE`, so every later filter runs inside its context.
- On each request it generates the values and sets the response headers before calling the chain, so they exist even if a later stage fails. It then stores the context object in an exchange attribute and calls `withContext(RequestContext(...) + MDCContext(MDC map + ids)) { chain.filter(exchange) }`.
- `CoWebFilter`'s chain wrapper saves `currentCoroutineContext().minusKey(Job)` in `COROUTINE_CONTEXT_ATTRIBUTE`. Spring passes that context to `InvocableHandlerMethod`, so a `suspend` controller sees the element and the MDC values, across dispatcher switches.
- Code outside coroutines, such as a `WebExceptionHandler`, reads the exchange attribute. It sets MDC around its own log calls (`MDC.putCloseable`), because it runs after the filter's `withContext` has ended.
- Core never reads the element. It takes values such as `RequestId` as parameters, which keeps policies pure.

Canonical use: FND-04 spec, section 5 (`docs/specs/FND-04-request-context-and-errors.md`).

## Why

- It is cheaper than Micrometer context propagation for one value: no automatic-propagation hook, no `ThreadLocalAccessor`.
- It still works with Spring's documented coroutine context propagation through `CoWebFilter`.
- OBS-01 can add `PropagationContextElement` and context propagation for Reactor-operator threads without changing this shape.
- Sources:
  - https://github.com/spring-projects/spring-framework/blob/7.0.x/spring-web/src/main/kotlin/org/springframework/web/server/CoWebFilter.kt
  - https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html
  - https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-slf4j/kotlinx.coroutines.slf4j/-m-d-c-context/

## Gotchas / pitfalls

- `MDCContext` captures the map when it is constructed. A later `MDC.put` inside the coroutine is lost on the next suspension. Add values by creating a new `MDCContext` in a nested `withContext`.
- Reactor callbacks outside the coroutine (for example a `WebClient` exchange in an adapter) do not see this MDC until Micrometer context propagation is enabled (OBS-01).
- Exchange attributes and coroutine elements are per request. Never cache them in a bean.
- Functional `coRouter` handlers: check that they receive the attribute-carried context before relying on it (unverified in FND-04).
