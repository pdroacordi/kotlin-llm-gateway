---
id: bugs/noleak-test-canary-key-never-reaches-the-adapter
type: bug
title: NoLeakIntegrationTest never configures its canary key, so "the key is not in the log" proves nothing
status: active
severity: medium
tags: [testing, secrets, spring-test]
related: [systems/provider-adapters, gotchas/dynamic-property-source-superclass-registers-last]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/NoLeakIntegrationTest.kt
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/MockBackedTest.kt
---

## Summary

The GW-01 criterion "the key is `sk-canary-key`, absent from the output and the response" is not tested: the adapter runs with `provider-key-not-real`, so `sk-canary-key` cannot appear whatever the code does. A real key leak passes this test.

## Symptom

Found in the GW-01 review. Spring's own `DynamicPropertiesContextCustomizerFactory`, run on the compiled test class, registers in this order: `NoLeakIntegrationTest.canaryKey`, then `MockBackedTest.mockProvider`. The final value of `llmgw.providers.openai.api-key` is `provider-key-not-real`.

## Root cause

`NoLeakIntegrationTest` claimed "Registered after MockBackedTest's", in a comment the fix removed. The order is the reverse: `MethodIntrospector.selectMethods` walks the class before its superclass, and the registry is a `Map.put`, so the superclass's method, called last, wins. The test never asserts which key the provider saw.

A second weakness in the same test: `assertNoCanary` reads `output.all` as soon as the response arrives, but the mapper logs a 5xx in `doOnSuccess`, after the write (`BlockHoundIntegrationTest.kt:94` says so and waits). For 500 and 503 the ERROR line can come after the assertion, so that line is not reliably covered.

## Fix

`MockBackedTest.KEY` is the canary, `sk-canary-key` (`MockBackedTest.kt:107`), and the subclass's `@DynamicPropertySource` is gone. `NoLeakIntegrationTest.kt:35` asserts `credential.fingerprint` in the journal equals its SHA-256 prefix; red before the change (`expected: <caff29ad> but was: <d5f7d442>`, 9 of 9). `awaitMapperLine` (`:40`) waits for the mapper's `with status` line before absence is asserted.

The Symptom and Root cause lines describe the code before the fix. Commit: the GW-01 commit (Roadmap: GW-01).

## Prevention

A canary test first proves the canary was in play: every "X is absent" assertion needs a positive check that X was present at the source. The test's own clients send and read bytes (`MockBackedTest.kt:53`, `:80`, `:94`), because with `logging.level.web=TRACE` Spring logs a `String` body in the same output.
