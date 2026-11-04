---
id: bugs/provider-key-with-newline-passes-startup-then-every-call-503
type: bug
title: A provider key with a trailing newline passes startup, then every call is a 503 with health UP
status: active
severity: low
tags: [configuration, adapters, secrets]
related: [systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/OpenAiProperties.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/ApiKey.kt
---

## Summary

`toAdapterConfig` rejects only a blank key. `OPENAI_API_KEY` with a trailing `\n`, the usual result of a secret file written with `echo`, starts cleanly. Every request then fails before a byte is sent, with 503 `provider_unavailable`. ADR-GW-01-012 rejected exactly this shape: "a misconfiguration surfaces only on the first paid-for call, and health says UP".

## Symptom

Found in the GW-01 review with the real adapter, key `sk-canary-key\n`: zero requests reached the stub, the result was `ProviderUnavailable`, and Reactor Netty logged `IllegalArgumentException: Validation failed for header 'Authorization'` at WARN once per call. The key itself was not in the output, at DEBUG either.

## Root cause

`OpenAiProperties.kt:33` checks `isBlank`; Netty validates the header per request, and WebClient wraps that as `WebClientRequestException`, which `OpenAiTransportFailures` reads as "cannot reach the provider".

## Fix

`ApiKey.isVisibleAscii` (`ApiKey.kt:10`) is true when every character is `0x21..0x7E`, and `toAdapterConfig` (`OpenAiProperties.kt:35`) refuses anything else, naming `OPENAI_API_KEY` and not the value. Such a call could also no longer read as "cannot reach the provider": `OpenAiTransportFailures` now gives 503 only when nothing was sent, for a known reason.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

`ApiKeyTest`, `OpenAiPropertiesTest` "a key with anything outside visible ASCII names the variable, never the value" and `StartupFailureTest` "a key with a trailing newline stops the app"; all red first.
