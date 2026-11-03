---
id: patterns/singleton-test-containers-mirroring-compose-images
type: pattern
title: One lazily started Testcontainers singleton per backing service, with images kept equal to the compose file by a Docker-free test
status: active
tags: [testcontainers, testing, docker, compose, integration-test]
related: [systems/local-stack, systems/mock-provider, patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/StackImages.kt
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/SharedPostgres.kt
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/SharedRedis.kt
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/SharedRedpanda.kt
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/SharedMockProvider.kt
  - test-support/src/test/kotlin/io/github/pdroacordi/llmgateway/testsupport/StackContractTest.kt
---

## Summary

Integration tests in several modules need the same backing services (Postgres, Redis, a broker, the mock provider), each slow to start. Each service is a Kotlin `object` holding one Testcontainers container, created and started in a synchronized `lazy` on first access and never stopped by code. The images live in one `StackImages` object that a Docker-free test keeps equal to `deploy/compose.yaml`.

## Details

- One object per service in `test-support` (`.testsupport`): `SharedPostgres.container`, `SharedRedis.container` and `.uri`, `SharedRedpanda.container` and `.bootstrapServers`, `SharedMockProvider.container`, `.baseUrl` and `.reset()`. The shape, from `SharedRedpanda.kt`:

  ```kotlin
  object SharedRedpanda {
      val container: RedpandaContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
          RedpandaContainer(StackImages.REDPANDA)
              .withStartupTimeout(Duration.ofSeconds(90))
              .also { it.start() }
      }
  }
  ```

- Started on first access, in whichever test touches it first; concurrent first accesses wait for the same start (`SharedPostgresTest` "two concurrent first accesses get the same started container" asserts one `containerId`). Nothing calls `stop()`: Ryuk, the Testcontainers sidecar, removes the containers when the test JVM ends. Gradle forks one JVM per module's `integrationTest`, so a container is shared by the test classes of one module and never across modules.
- Where Kotlin cannot infer `GenericContainer`'s self type, a private subclass fixes it (`SharedRedis.RedisContainer`, as in `MockProviderImageTest`).
- `StackImages` (`const val POSTGRES`, `REDIS`, `REDPANDA`) mirrors the compose file. `StackContractTest` runs in the Docker-free `test` suite, parses `deploy/compose.yaml` with SnakeYAML and fails naming the service and both values when they differ; the compose file is a declared input of the `test` task, so an edit there reruns it. The same test enforces the compose file's port and naming rules, and its drift cases are permanent tests over small in-memory compose documents.
- Isolation is the consumer's job, written as KDoc on each object: a schema or database per test class (Postgres), key prefixes or `FLUSHDB` in `@BeforeEach` (Redis; Gradle runs a module's test classes one after another), a unique topic per test (Redpanda), `reset()` in `@BeforeEach` (mock).
- ARCH-11 keeps `org.testcontainers` and `.testsupport` in `integrationTest` sources (and test-support's own `main`), so the fixtures cannot leak into `test` or production code.

## Why

- Composition over a base class: Testcontainers' documented singleton-container shape is an abstract base class with a static container, but a test needing Postgres and Redis cannot extend two base classes; it can read two objects. (Testcontainers docs, "Manual container lifecycle control", singleton containers.)
- A singleton outlives every Spring context in the JVM. Spring Boot stops container beans when their cached context closes, and warns that JUnit-extension containers can be stopped while a cached context still uses them (Spring Boot reference, "Testcontainers"). An object no context owns cannot be stopped by one.
- One start per service per module run instead of one per class (`@Testcontainers`/`@Container`), and no reuse opt-in file (`withReuse(true)`), which leaves state across runs and does not apply in CI.
- The compose file stays the single truth for versions (ADR-FND-02-008): fixtures do not parse YAML at runtime, and drift fails the fast suite without Docker.

## Gotchas / pitfalls

- Never register a shared container as a bean its context may stop (a `@Bean` returning `SharedPostgres.container` is destroyed with the context). For `@ServiceConnection`, use a form the first app item proves does not stop it (spec FND-02 U12).
- Never call `stop()` on a shared container, and never pause it in a test; start a private container for failure tests.
- Do not disable Ryuk (`TESTCONTAINERS_RYUK_DISABLED=true`): nothing else removes the singletons.
- Never point integration tests at the dev stack (`deploy/compose.yaml`): its ports are the developer's, its data persists, and a worktree's ports differ.
- A version bump edits `deploy/compose.yaml` and `StackImages` together; `./gradlew test` fails until both agree.
- A container that dies mid-run is not restarted: later tests in that JVM fail, which surfaces the problem instead of hiding it.
