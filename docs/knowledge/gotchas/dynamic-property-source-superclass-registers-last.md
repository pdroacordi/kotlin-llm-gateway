---
id: gotchas/dynamic-property-source-superclass-registers-last
type: gotcha
title: A @DynamicPropertySource beats @TestPropertySource, and in a test hierarchy the superclass's method runs last, so it beats the subclass's too
status: active
tags: [testing, spring-test]
related: [bugs/noleak-test-canary-key-never-reaches-the-adapter, gotchas/integration-test-suite-has-no-test-resources]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/MockBackedTest.kt
---

## Summary

Spring Test 7.0.10 puts dynamic properties first in the environment, so a subclass cannot override an inherited dynamic property with `@TestPropertySource`, and cannot with its own `@DynamicPropertySource` either.

## Details

- Precedence: `DynamicValuesPropertySource` is added with `MutablePropertySources.addFirst`.
- Order: `DynamicPropertiesContextCustomizerFactory` collects methods with `MethodIntrospector.selectMethods`, which lists a class before its superclass. The registry is a `Map.put`, so the last call wins, and that is the superclass.

Observed on the compiled `NoLeakIntegrationTest`: order `canaryKey`, then `MockBackedTest.mockProvider`; final value `provider-key-not-real`.

## How to handle it

To vary an inherited dynamic value, read it in the base method from an overridable place, or do not extend the base (`ClosedProviderPortTest`). After any override, assert the value that took effect.
