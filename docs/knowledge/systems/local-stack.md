---
id: systems/local-stack
type: system
title: Local stack and test fixtures: seven dev services in deploy/compose.yaml on loopback ports, and lazily started Testcontainers singletons in test-support
status: active
tags: [docker, compose, testcontainers, postgres, redis, redpanda, otel, jaeger, prometheus, grafana, testing]
related: [patterns/singleton-test-containers-mirroring-compose-images, patterns/architecture-rule-with-violating-fixture, systems/mock-provider, bugs/grafana-anonymous-admin-reachable-through-dns-rebinding, gotchas/grafana-serves-any-host-header-unless-enforce-domain, gotchas/jaeger-2-22-legacy-search-api-404-breaks-grafana-health, gotchas/postgres-18-pg-isready-socket-ready-during-init-server, gotchas/docker-desktop-publishes-ports-held-by-windows-processes, gotchas/bash-xtrace-pollutes-captured-stderr, runs/2026-11-03-smoke-local-stack, gotchas/gradle-configuration-cache-rejects-script-object-in-lambda, gotchas/git-bash-grep-ignore-case-fixed-strings-aborts, gotchas/konsist-paths-and-module-names-use-platform-separator]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
  - deploy/.env.example
  - deploy/otel-collector/config.yaml
  - deploy/prometheus/prometheus.yml
  - deploy/grafana/provisioning/datasources/datasources.yaml
  - tools/smoke/stack-check.sh
  - scripts/preflight.sh
  - .github/workflows/stack.yml
  - build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts
  - build-logic/src/main/kotlin/llmgw.kotlin-jvm.gradle.kts
  - test-support/build.gradle.kts
  - test-support/src/main/kotlin/io/github/pdroacordi/llmgateway/testsupport/StackImages.kt
  - test-support/src/test/kotlin/io/github/pdroacordi/llmgateway/testsupport/StackContractTest.kt
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

`deploy/compose.yaml` runs the infrastructure every later item writes to: Postgres, Redis, Redpanda, an OpenTelemetry collector, Jaeger, Prometheus and Grafana, each on a `127.0.0.1` port from an `LLMGW_*_PORT` variable whose default is the CLAUDE.md port. OTLP sent to the collector shows up in Jaeger (traces) and Prometheus (metrics), and Grafana has both as datasources. `tools/smoke/stack-check.sh` proves a running stack end to end. Integration tests never use this stack: `test-support` gives each test JVM its own lazily started Testcontainers containers with the same images. Contract details: [FND-02 spec](../../specs/FND-02-local-stack.md), section 4.

## Contract (never break)

1. Every published port binds `127.0.0.1` and comes from an `LLMGW_*_PORT` variable whose default is the CLAUDE.md port, mapped to the documented container port; no service sets `network_mode`. `StackContractTest` (real file, plus a `0.0.0.0` binding, a missing variable, a wrong default, a wrong container port and a `network_mode` as violations); `stack-check.sh` check `loopback`.
2. An image tag changes in `deploy/compose.yaml` and `StackImages` together. `StackContractTest` "an image that differs from StackImages is named with both values".
3. No `container_name`. The project name comes from `name: llmgw` or `COMPOSE_PROJECT_NAME`, so two projects never share a container, network or volume. `StackContractTest` "a container_name is rejected".
4. Nothing runs `down -v` on a stack it did not create. Dev data lives only in the named volumes `postgres-data`, `redpanda-data`, `prometheus-data`, `grafana-data` (prefixed with the project name). `.claude/settings.json` denies `down -v` and `down --volumes` in any `docker compose`/`docker-compose` spelling (flags before or after `down`, `-p <name>` included), `docker volume rm llmgw_*` and `docker volume prune` (user, 2026-11-03: "Yes, widen it"). An agent removes a throwaway project it created with `down` plus `docker volume rm <project>_<volume>`. Only CI's ephemeral stack (`stack.yml`) removes its volumes.
5. Integration tests never use the dev stack. Testcontainers and `org.testcontainers` live only in `integrationTest` sources of non-core modules and test-support's `main`; test-support is referenced only from `integrationTest` sources (ARCH-11, `TestSupportRulesTest`, fixtures `arch-11-*`). In the build, `checkTestSupportClasspath` (in `check`, every module but test-support) fails when test-support or `org.testcontainers` resolves on a `main` or `test` classpath. A `Shared*` container is never stopped by a test or a Spring context.
6. Grafana datasource UIDs `prometheus` and `jaeger` never change. `stack-check.sh` check `grafana`.
7. Grafana's anonymous Admin answers only `Host: 127.0.0.1` (`GF_SERVER_ENFORCE_DOMAIN`): a write naming another host, as a DNS-rebinding page sends, is redirected and creates nothing. `stack-check.sh` check `grafana-host`.

Reserved for later: compose profiles `apps` (OPS-03) and `mock` (OPS-01). Services without a profile always start, so `up -d` stays infrastructure-only (ADR-FND-02-005).

## How it works

**Services** (`deploy/compose.yaml`). Exact image tags, no digests. No `restart` policy (a crash stays visible), `json-file` logs rotated at 10 MB × 3, memory limits as ceilings (`deploy.resources.limits.memory`, applied by plain `docker compose`):

| Service | Image | Host port (default) | Healthcheck | Volume | Memory |
|---|---|---|---|---|---|
| postgres | `postgres:18.6-trixie` | 5440 | `pg_isready -h 127.0.0.1` (TCP: not ready during the entrypoint's init) | `postgres-data:/var/lib/postgresql` | 512m |
| redis | `redis:8.10.3-trixie` | 6390 | `redis-cli ping` | none (`--save ""`, `--appendonly no`) | 512m |
| redpanda | `docker.redpanda.com/redpandadata/redpanda:v26.2.6` | 19092 | `rpk cluster health` | `redpanda-data` | 1536m |
| otel-collector | `otel/opentelemetry-collector:0.164.0` | 4317, 4318 | none (`scratch` image) | none | 256m |
| jaeger | `jaegertracing/jaeger:2.22.0` | 16686 | none | none (memory store, 100 000 traces) | 1g |
| prometheus | `prom/prometheus:v3.15.1-busybox` | 9090 | `wget /-/ready` | `prometheus-data` (7 d or 1 GB) | 512m |
| grafana | `grafana/grafana:13.3.0` | 3301 | `wget /api/health` | `grafana-data` | 512m |

Redpanda advertises `external://127.0.0.1:${LLMGW_KAFKA_PORT}` so a host client reaches the broker on the published port; `127.0.0.1` rather than `localhost`, which may resolve to `::1` first. Its external listener (`--kafka-addr external://0.0.0.0:19092`) is the published container port (`StackContractTest`). Grafana's default is 3301, not 3001: Next.js dev servers take 3000 and 3001.

**Telemetry path** (`deploy/otel-collector/config.yaml`, `deploy/prometheus/prometheus.yml`). OTLP enters only through the collector (gRPC 4317, HTTP 4318, bound `0.0.0.0` inside the container). Pipelines: `traces` → `memory_limiter`, `batch` → `otlp_grpc/jaeger` (`jaeger:4317`, insecure); `metrics` → same processors → `prometheus` exporter on `:8889`. The collector's own telemetry is on `:8888`. Prometheus scrapes `prometheus`, `otel-collector` (`:8888`) and `otel-collector-exporter` (`:8889`) every 15 s. An OTLP gauge `stack.check.heartbeat` appears in Prometheus as `stack_check_heartbeat`. No `debug` exporter and no logs pipeline: a `debug` exporter at `detailed` verbosity copies every span attribute into the container log.

**Grafana** (`deploy/grafana/provisioning/datasources/datasources.yaml`): anonymous Admin on loopback, with the host enforced: `GF_SERVER_DOMAIN=127.0.0.1`, `GF_SERVER_ENFORCE_DOMAIN=true`, `GF_SERVER_ROOT_URL=http://127.0.0.1:${LLMGW_GRAFANA_PORT:-3301}/`. Loopback keeps the LAN out but not a browser on the same machine; without the host check a DNS-rebinding page could administer Grafana ([bug](../bugs/grafana-anonymous-admin-reachable-through-dns-rebinding.md)). Open it as `http://127.0.0.1:<port>`; `localhost` is redirected there. Datasources `Prometheus` (uid `prometheus`, default) and `Jaeger` (uid `jaeger`), not editable. The Jaeger datasource looks up traces by ID only: it cannot search Jaeger 2.22.0 (Gotchas), so OBS-02 dashboards must not rely on Jaeger search through Grafana.

**Worktrees.** With `-f deploy/compose.yaml` the project directory is `deploy/`, and Compose reads `deploy/.env` for interpolation and for `COMPOSE_PROJECT_NAME`, also when run from the repository root (verified 2026-11-03 with two projects side by side). A worktree copies `deploy/.env.example` to `deploy/.env` and sets the project name and every port (+100 suggested). Tools read the same file: `set -a; . deploy/.env; set +a`. `scripts/preflight.sh` notes a linked worktree that has no `deploy/.env`, and, while the project is down, warns about each stack port (exported variable, then `deploy/.env`, then the default) that another process already listens on, naming the PID (`netstat -ano` on Windows, `ss -ltnp` on Linux). Apps change their own port with `SERVER_PORT` on `bootRun`.

**Stack check** (`tools/smoke/stack-check.sh`). Reads `deploy/.env` as `KEY=VALUE` lines (an exported variable wins, as in Compose), then runs twelve checks and prints `PASS <check>` or `FAIL <check>: <evidence>`; exit 1 if any failed. Checks: `services` (FAIL when `config --services` or `ps --services` itself fails), `health`, `loopback`, `postgres` and `redis` (in the container and over the host port through `/dev/tcp`), `redpanda` (health and the advertised address), `jaeger-ui`, `traces` (an OTLP/HTTP span with a random trace ID, found at `/api/traces/<id>` within 30 s), `metrics` (a gauge with a random `run_id` label, found in Prometheus within 45 s), `targets`, `grafana`, `grafana-host` (a `POST /api/datasources` with `Host: rebind.example` must not get 2xx and must create nothing; a datasource it did create is deleted again). Debug it with `BASH_XTRACEFD`, never plain `bash -x` ([bash-xtrace-pollutes-captured-stderr](../gotchas/bash-xtrace-pollutes-captured-stderr.md)). It starts and stops nothing and leaves one synthetic trace (service `llmgw-stack-check`) and one gauge.

**Fixtures** (`test-support`, role `tool`, root `.testsupport`). `StackImages` holds the Postgres, Redis and Redpanda images; `StackContractTest` (Docker-free, in `./gradlew test`) parses `deploy/compose.yaml` and keeps them equal, plus the port and naming rules. `SharedPostgres`, `SharedRedis`, `SharedRedpanda` and `SharedMockProvider` each hold one container created and started inside a synchronized `lazy` on first access, never stopped by code; Ryuk removes it when the JVM ends. Pattern: [singleton-test-containers-mirroring-compose-images](../patterns/singleton-test-containers-mirroring-compose-images.md). Isolation is the consumer's job: a schema or database per test class, key prefixes or `FLUSHDB`, a topic per test, `SharedMockProvider.reset()` in `@BeforeEach`. `SharedPostgres.database(name)` creates a database once and returns its coordinates, for `@DynamicPropertySource`; a repeat is a no-op (GW-04).

**First consumer.** `gateway-app` needs compose Postgres to start, in `local` too (`docker compose -f deploy/compose.yaml up -d --wait postgres`): Flyway runs `V1` over JDBC at startup, and R2DBC serves requests ([auth-and-teams](auth-and-teams.md)). It reads `LLMGW_POSTGRES_HOST`, `_PORT`, `_DB`, `_USER` and `_PASSWORD`, whose defaults are the compose values; only the password needs `local` for its default (ADR-GW-04-016). `src/test` runs with Flyway off and a closed-port R2DBC URL, so it cannot touch this stack.

**Consumer wiring** (`llmgw.integration-test`). A module adds `integrationTestImplementation(project(":test-support"))`; its `testDependsOn` in `Modules.kt` must list `test-support` (gateway-providers, gateway-infra, gateway-app, agent-app and cli do; core modules, mock-provider, evals and architecture-tests do not). For the mock image it adds `mockProvider(project(":mock-provider"))`: the convention resolves `mockProviderJar` (attribute `Category` = `mock-provider-boot-jar`, the variant `mock-provider` publishes), declares the jar and `mock-provider/Dockerfile` as inputs of `integrationTest`, and passes `-DmockProvider.jar=` and `-DmockProvider.dockerfile=`. A module without the dependency gets neither.

## Failure behaviour

- A host port already taken by another container: `up` fails with Docker's "port is already allocated". Taken by a Windows process on Docker Desktop: `up` succeeds, the services report healthy, and `127.0.0.1:<port>` keeps reaching the other program ([gotcha](../gotchas/docker-desktop-publishes-ports-held-by-windows-processes.md)); `scripts/preflight.sh` warns about it before `up`, and `stack-check.sh` fails on the affected check. Either way, set the variable in `deploy/.env`. No fallback to a random port.
- A service crashes: it stays down; `ps` and `stack-check.sh` show it. A stopped Redis fails `services`, `health` and `redis` (verified).
- Collector or Jaeger misconfigured: neither has a healthcheck, so `up --wait` counts them ready while running; the `traces` and `metrics` checks are the real proof.
- Simulating a dependency failure for later items: `docker compose -f deploy/compose.yaml stop redis` (refused) or `pause redis` (hung), then `start` or `unpause`. In tests, pause a private container, never a `Shared*` one.
- No Docker daemon in `integrationTest`: Testcontainers' error, never a skip (ADR-016). An image pull failure fails the fixture's first access and the test class.
- A fixture container dies mid-run: later tests in that JVM fail on connection; nothing restarts it.
- Ryuk disabled (`TESTCONTAINERS_RYUK_DISABLED=true`): the singletons leak after the JVM ends. Do not disable it.

## Configuration

| Name | Where | Default | Note |
|---|---|---|---|
| `COMPOSE_PROJECT_NAME` | shell or `deploy/.env` | `llmgw` | lowercase letters, digits, `-`, `_` |
| `LLMGW_POSTGRES_PORT`, `LLMGW_REDIS_PORT`, `LLMGW_KAFKA_PORT`, `LLMGW_OTLP_GRPC_PORT`, `LLMGW_OTLP_HTTP_PORT`, `LLMGW_JAEGER_UI_PORT`, `LLMGW_PROMETHEUS_PORT`, `LLMGW_GRAFANA_PORT` | same | 5440, 6390, 19092, 4317, 4318, 16686, 9090, 3301 | TCP port on 127.0.0.1 |
| `LLMGW_POSTGRES_PASSWORD` | same | `llmgw` | local-only credential on a loopback port, not a secret |
| `mockProvider.jar`, `mockProvider.dockerfile` | `integrationTest` JVM system properties | absent unless the module declares `mockProvider(...)` | absolute paths |
| Fixture start timeouts | `Shared*` constants | Postgres 60, Redis 30, Redpanda 90, mock 90 | s; mock `reset()` 10 s |

Measured 2026-11-03 on Docker Desktop 29.3.1 (Windows 11): `up -d --wait` with images pulled 13 to 18 s; idle memory about 600 to 690 MiB in total. Grafana sat at its former 384m ceiling with 255 to 276 MiB anonymous memory, the rest page cache (smoke run), so its limit is 512m; `stack-check.sh` 11 to 21 s; `./gradlew :test-support:integrationTest` 41 s including the mock image build.

## Decisions

- [FND-02 spec](../../specs/FND-02-local-stack.md): ADR-FND-02-001 (images, exact tags), -002 (ports, project name, worktrees), -003 (telemetry path), -004 (healthchecks, volumes, limits, no restart, log rotation), -005 (apps and mock not in compose yet), -006 (the test-support module), -007 (singleton lifecycle), -008 (compose as the version truth), -009 (no Spring Boot Docker Compose support), -010 (mock consumer wiring), -011 (path-filtered CI), -012 (this entry).
- Cross-cutting: [ADR-016](../../DECISIONS.md#adr-016) (the `integrationTest` suite), [ADR-017](../../DECISIONS.md#adr-017) (test-support and ARCH-11), [ADR-010](../../DECISIONS.md#adr-010) (Redpanda is provisional).
- User decision, 2026-11-03: Redpanda and Redis 8 images accepted for local and CI use ("Redpanda + Redis 8 (Recommended)").

## Gotchas

- Jaeger 2.22.0 has no route for the legacy search API, so Grafana 13.3.0's Jaeger datasource fails its health check and cannot search; lookup by trace ID works ([jaeger-2-22-legacy-search-api-404-breaks-grafana-health](../gotchas/jaeger-2-22-legacy-search-api-404-breaks-grafana-health.md)). `stack-check.sh` checks that the datasource exists, not its health (spec U6 fallback).
- Grafana serves any `Host` unless `enforce_domain` is on ([grafana-serves-any-host-header-unless-enforce-domain](../gotchas/grafana-serves-any-host-header-unless-enforce-domain.md)).
- Docker Desktop publishes a port a Windows process already holds, and `up` succeeds ([docker-desktop-publishes-ports-held-by-windows-processes](../gotchas/docker-desktop-publishes-ports-held-by-windows-processes.md)).
- The `jaegertracing/jaeger:2.22.0` image already sets `JAEGER_LISTEN_HOST=0.0.0.0`; the compose file sets it again so an image without it cannot fall back to `localhost`.
- `pg_isready` over the Unix socket reports ready during the entrypoint's temporary init server; over `-h 127.0.0.1` it stays not ready (exit 2) until the real server listens. Keep `-h 127.0.0.1` ([postgres-18-pg-isready-socket-ready-during-init-server](../gotchas/postgres-18-pg-isready-socket-ready-during-init-server.md)).
- Git Bash on Windows needs `MSYS_NO_PATHCONV=1` for a `docker run -v "$PWD/...:/container/path"` volume.
- Konsist names `src/integrationTest/kotlin` source set `integrationTest` (verified by `TestSupportRulesTest`); fixtures all report `fixtures`, so `RuleFixturesTest` derives the source set from the path segment after `<module>/src/` ([konsist-paths-and-module-names-use-platform-separator](../gotchas/konsist-paths-and-module-names-use-platform-separator.md)).
- The convention's argument provider captures only locals ([gradle-configuration-cache-rejects-script-object-in-lambda](../gotchas/gradle-configuration-cache-rejects-script-object-in-lambda.md)). `stack-check.sh` never uses `grep -iF` ([git-bash-grep-ignore-case-fixed-strings-aborts](../gotchas/git-bash-grep-ignore-case-fixed-strings-aborts.md)).
