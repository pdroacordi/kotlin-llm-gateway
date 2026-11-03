---
id: gotchas/detekt-forbiddenvoid-flags-mono-void
type: gotcha
title: detekt's ForbiddenVoid is active by default and flags every Mono<Void> type argument
status: active
tags: [detekt, static-analysis, webflux]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - config/detekt/detekt.yml
---

## Summary

In detekt 2.0.0-alpha.6's default configuration, `style.ForbiddenVoid` has `active: true`, `ignoreOverridden: false` and `ignoreUsageInGenerics: false`. Every WebFlux contract that returns `Mono<Void>` is therefore a finding. This includes `WebExceptionHandler.handle`, `BodyInserter.insert` and `ServerHttpResponse.writeWith`. `Unit` cannot stand in for these types.

## Details

The defaults come from the generated default config (`detektGenerateConfig` output). `ignoreOverridden: true` covers only override signatures. Private helpers that return `Mono<Void>`, such as `GatewayErrorWebExceptionHandler.writeReply`, are still flagged.

## How to handle it

`config/detekt/detekt.yml` sets `style.ForbiddenVoid.ignoreUsageInGenerics: true`. A bare `Void` outside generics is still flagged.
