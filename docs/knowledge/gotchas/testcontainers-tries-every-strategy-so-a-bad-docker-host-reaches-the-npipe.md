---
id: gotchas/testcontainers-tries-every-strategy-so-a-bad-docker-host-reaches-the-npipe
type: gotcha
title: A bad DOCKER_HOST does not stop Testcontainers on Windows, because it falls through every applicable strategy to the npipe
status: active
tags: [testcontainers, docker, windows, testing]
related: [systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts
---

## Summary

With Testcontainers 2.0.5 on Windows, `DOCKER_HOST=tcp://127.0.0.1:1 ./gradlew :test-support:integrationTest` still passes (exit 0, 11 tests). So does adding `TESTCONTAINERS_DOCKER_CLIENT_STRATEGY=...EnvironmentAndSystemPropertyClientProviderStrategy`. Neither can simulate a missing daemon while Docker Desktop runs.

## Details

`DockerClientProviderStrategy.getFirstValidStrategy` (bytecode) builds one list, the `tc.host` strategy, the environment strategy, the configured strategy, then every other strategy by priority, and takes the first that answers `docker info`. A failing override only moves on to the next entry. `NpipeSocketClientProviderStrategy` has `npipe:////./pipe/docker_engine` as a constant and is applicable when commons-lang's `SystemUtils.IS_OS_WINDOWS` is true, so no environment variable or property points it elsewhere.

## How to handle it

To prove a suite fails without a daemon, without stopping Docker for other projects, hide the OS from the test JVM only, with an init script kept outside the repo:

```kotlin
allprojects { tasks.withType<Test>().configureEach { systemProperty("os.name", "NoNpipe") } }
```

`DOCKER_HOST=tcp://127.0.0.1:1 ./gradlew :test-support:integrationTest --rerun-tasks --no-configuration-cache --init-script <file>` then leaves the bad address as the only strategy: exit 1, 11 failed, 0 passed, 0 skipped, `IllegalStateException: Could not find a valid Docker environment`, and `Previous attempts to find a Docker environment failed. Will not retry` for the rest (2026-11-03, ADR-FND-03-012).
