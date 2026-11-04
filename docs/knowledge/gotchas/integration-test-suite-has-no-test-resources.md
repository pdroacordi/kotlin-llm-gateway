---
id: gotchas/integration-test-suite-has-no-test-resources
type: gotcha
title: The integrationTest suite does not see src/test/resources, so a Spring context there gets main's values, the real provider URL included
status: active
tags: [testing, gradle, spring-test]
related: [systems/provider-adapters, gotchas/dynamic-property-source-superclass-registers-last]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts
  - gateway-app/src/test/resources/application.properties
  - gateway-app/src/integrationTest/resources/application.properties
---

## Summary

`llmgw.integration-test` extends the suite's dependency configurations from `testImplementation`, which brings libraries, not the `test` source set's output. `gateway-app/src/test/resources/application.properties` (fake key, closed provider port) is therefore absent from `integrationTest`.

## Details

The suite depends on `project()` (main output) and on `testImplementation`'s dependencies. `gateway-app/src/integrationTest` has no `resources` directory. A `@SpringBootTest` there falls back to `application.yaml`: `base-url: https://api.openai.com/v1` and `api-key: ${OPENAI_API_KEY:}`. Without the variable the context fails on the blank key; with a real key in the shell it starts, and a test that forgets the override would call OpenAI.

## How to handle it

Give the suite its own `src/integrationTest/resources/application.properties` with the same values as the `test` one, so forgetting an override fails on a closed port, as CLAUDE.md's "tests never call a real provider" needs.

## Revision (2026-11-03)

`gateway-app/src/integrationTest/resources/application.properties` now exists, with the fake key and the closed port. `MockBackedTest` replaces both with the mock and its canary key, and `ClosedProviderPortTest` uses the file as it is. The Details above describe the suite before that file.
