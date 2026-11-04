---
id: patterns/port-fake-proved-by-a-shared-contract-test
type: pattern
title: An in-memory fake of a store port, in core testFixtures, proved equal to the real adapter by one shared contract test
status: active
tags: [testing, ports, testcontainers, spring, hexagonal]
related: [patterns/singleton-test-containers-mirroring-compose-images, gotchas/integration-test-suite-has-no-test-resources]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - docs/specs/GW-04-teams-and-api-keys.md
---

## Summary

Many Spring tests need a store port, and `./gradlew test` must stay Docker-free (ADR-016). They use an in-memory fake, and one abstract contract test runs against both the fake and the real adapter, so a green fake means what a green adapter means.

## Details

Planned in GW-04 for `TeamStorePort` (ADR-GW-04-010), not built yet; replace this line with `path:line` when it lands.

- **Fake and contract** live in the core module's `testFixtures` source set, plain Kotlin: `InMemoryTeamStore`, `abstract class TeamStoreContract { abstract fun store(): TeamStorePort }`. A consumer adds `testImplementation(testFixtures(project(":gateway-core")))`; `integrationTest` inherits it.
- **Two subclasses:** `InMemoryTeamStoreTest` in core's `test`, `PostgresTeamStoreIntegrationTest` in the adapter's `integrationTest`. A behaviour goes in the contract, never in one subclass. Failure simulation is the fake's own switch and a private paused container, outside the contract.
- **Wiring:** a plain `@Configuration` in the app's test sources is reached by component scan, so every `@SpringBootTest` and `SpringApplicationBuilder` test gets it with no per-class edit. It declares a `@Primary` fake and is off under a test-only property for suites that want the real adapter. `integrationTest` sees no `src/test` classes, so each source set has its own wiring of a few lines; the fake itself is not copied.
- **Dummy connection:** both test `application.properties` point the driver at a closed port and turn migrations off, so a test cannot touch the dev stack.

## Why

- Over `@Import` per class: no edit in each test, and `SpringApplicationBuilder` tests have no place for one.
- Over a production `kind=memory` switch: no way to run a deployment on a store that forgets.
- Over a fake with no contract: a fake drifts silently, and every test above it then proves the wrong thing.
- Contract tests for fakes are a long-standing idea: https://martinfowler.com/bliki/ContractTest.html

## Gotchas / pitfalls

- Kover reports `testFixtures` as application code unless excluded ([kover-excludes-only-the-test-compilation-by-default](../gotchas/kover-excludes-only-the-test-compilation-by-default.md)).
- A `@Configuration` in test sources is scanned; `@TestConfiguration` and `@TestComponent` are not. Use the plain one here, on purpose.
- A connection pool that connects eagerly would break the closed-port trick: prove it once, with no daemon running.
- The contract covers what callers rely on, not how the adapter is built; a test of an index or a constraint stays in the adapter's suite.
