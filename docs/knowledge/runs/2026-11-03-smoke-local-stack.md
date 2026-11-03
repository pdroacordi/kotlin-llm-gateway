---
id: runs/2026-11-03-smoke-local-stack
type: run
kind: smoke
title: FND-02 local stack smoke - compose up, stack-check.sh, manual checks of every service, a stopped-Redis negative check, down without -v
status: active
build: f67b652 + uncommitted FND-02
area_tested: "FND-02 local stack (deploy/compose.yaml, seven services) and tools/smoke/stack-check.sh"
verdict: pass-with-findings
tags: [smoke, compose, docker-desktop, postgres, redis, redpanda, otel, jaeger, prometheus, grafana, ports]
related: [systems/local-stack, runs/2026-11-03-smoke-mock-provider-and-errors, gotchas/docker-desktop-publishes-ports-held-by-windows-processes, gotchas/jaeger-2-22-legacy-search-api-404-breaks-grafana-health, gotchas/bash-xtrace-pollutes-captured-stderr]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
  - tools/smoke/stack-check.sh
  - deploy/grafana/provisioning/datasources/datasources.yaml
  - docs/specs/FND-02-local-stack.md
---

## What was run

- **Stack:** project `llmgw`, found down with its four volumes kept from the implementer's run, no `deploy/.env`. I brought it up (`up -d --wait --wait-timeout 180`) and brought it down without `-v` at the end. The images were already pulled: `postgres:18.6-trixie`, `redis:8.10.3-trixie`, `redpanda:v26.2.6`, `otel/opentelemetry-collector:0.164.0`, `jaegertracing/jaeger:2.22.0`, `prom/prometheus:v3.15.1-busybox`, `grafana/grafana:13.3.0`. Docker Desktop 29.3.1, Compose v5.1.1, Windows 11, Git Bash.
- **One override:** `LLMGW_GRAFANA_PORT=3101`, exported in the shell for `compose` and `stack-check.sh` (the script's precedence is: exported variable first). Port 3001 was taken by another project's Next.js dev server on `0.0.0.0` and `[::]` (PID 37648, started 16:38:22 local, one minute before this run; see finding 1). Every other port used its default. No file was written in `deploy/`.
- **Not in scope:** no app, no mock-provider, no real provider. FND-02 has no HTTP surface of ours.
- **Data left behind:** 4 synthetic spans (service `llmgw-stack-check`) in Jaeger's memory store, gone at `down`; 4 `stack_check_heartbeat` series in `llmgw_prometheus-data`, one per script run. The Redpanda topic `llmgw-smoke-fnd02-1793734954` was created and deleted. Nothing was written to Postgres or Redis.
- **Outside the stack:** a 60 s Python listener on `0.0.0.0:3199` and a container `llmgw-smoke-portprobe` (`redis:8.10.3-trixie`, `-p 127.0.0.1:3199:6379`) for finding 1. Both were removed and 3199 is free.

## Results

| # | Scenario | Expected | Observed | Result |
|---|---|---|---|---|
| S1 | `up -d --wait --wait-timeout 180` from down | exit 0, 7 running, 5 healthy, < 60 s | exit 0, about 11.7 s (first container created 19:39:48.45Z, Grafana's first healthy probe 19:40:00.10Z); `ps`: 7 up, postgres/redis/redpanda/prometheus/grafana `healthy`; Postgres log `Skipping initialization` (volume reused) | pass |
| S2 | `docker inspect .HostConfig.Memory` | section 5 limits | grafana 402653184, jaeger 1073741824, otel-collector 268435456, postgres/prometheus/redis 536870912, redpanda 1610612736 | pass |
| S3 | `bash tools/smoke/stack-check.sh` | 11 PASS, exit 0 | 11 PASS, exit 0, 11.2 s; a later run (after S10) also passed, in 19.1 s. An earlier attempt under `bash -x` was invalid (finding 5) | pass |
| S4 | Grafana UI and datasources (:3101) | UI answers; `prometheus` and `jaeger` exist; Prometheus healthy | `GET /` 200 `<title>Grafana</title>`; `/api/health` `database: ok`, `13.3.0`; datasources `prometheus` (default, `http://prometheus:9090`, readOnly) and `jaeger` (`http://jaeger:16686`, readOnly); `uid/prometheus/health` 200 `status: OK`; `uid/jaeger/health` 400 `{"message":"request failed: 404 Not Found","status":"ERROR"}` (known, spec U6 fallback) | pass |
| S5 | Grafana -> Jaeger queries | lookup by trace ID works | `POST /api/ds/query` `{"uid":"jaeger","query":"8ca52f67…c1a7"}`: 200, one frame, traceID `8ca52f67ae2a549cf56ca146dc31c1a7`, service `llmgw-stack-check`; proxy `/api/datasources/proxy/uid/jaeger/api/traces/32d0f1cf…4961` 200. Search `queryType: search, service: llmgw-stack-check`: 400, `"error":"request failed: 404 Not Found","errorSource":"downstream"` (finding 2) | pass-with-finding |
| S6 | Jaeger UI (:16686) and trace by ID | UI loads; stack-check trace findable | `GET /` 200 `<title>Jaeger UI</title>`; `/trace/32d0f1cfbdfdf56037caebbfa3074961` 200; `/api/traces/<id>` one span `stack-check`, 1000 µs, service `llmgw-stack-check`; `/api/v3/services` `["llmgw-stack-check","jaeger"]`; v3 search 19:40-19:41Z returns both of that minute's traces; legacy `/api/services` and `/api/traces?service=` 404; the UI's search code calls `/api/v3` (`useTraceDiscovery` chunk), so search works in the Jaeger UI | pass |
| S7 | Prometheus (:9090) targets | 3 targets up | `otel-collector` (`:8888`), `otel-collector-exporter` (`:8889`), `prometheus`: all `up`, `lastError` empty, scrape 0.8-2.6 ms; `/query` 200; `stack_check_heartbeat` has one series per `run_id` (job `otel-collector-exporter`); version 3.15.1 | pass |
| S8 | Postgres (:5440) with psql | reachable, 18.6 | in the container: `PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)`, `current_user`/`current_database` `llmgw`; databases `llmgw, postgres, template0, template1` (no leftover `fnd02_check`); `psql -h host.docker.internal -p 5440`: `via host port ok` | pass |
| S9 | Redis (:6390) PING | `+PONG` | host `/dev/tcp/127.0.0.1/6390`: `+PONG`, `redis_version:8.10.3`; `CONFIG GET save` empty, `appendonly no`; `DBSIZE` 0 | pass |
| S10 | Redpanda (:19092) with rpk | healthy; topic round trip; host address advertised | `rpk cluster health` `Healthy: true`, 0 leaderless/under-replicated; `cluster info -X brokers=127.0.0.1:19092` broker `0* 127.0.0.1 19092`; topic `llmgw-smoke-fnd02-1793734954`: create OK, produce `partition 0 offset 0`, consume `k1=hello-fnd02-smoke offset=0`, delete OK, list empty after; a raw Kafka Metadata v0 request **from the host** to 127.0.0.1:19092 gives `node 0 advertised 127.0.0.1:19092`; rpk v26.2.6 | pass |
| S11 | `docker stats --no-stream` vs limits | under limits; total < 2.5 GB | see the memory table below; total 602 MiB, then 581 MiB | pass (finding 3) |
| S12 | `docker port` on each service | 127.0.0.1 only | `5432->127.0.0.1:5440`, `6379->127.0.0.1:6390`, `19092->127.0.0.1:19092`, `4317->127.0.0.1:4317`, `4318->127.0.0.1:4318`, `16686->127.0.0.1:16686`, `9090->127.0.0.1:9090`, `3000->127.0.0.1:3101`; no `0.0.0.0` or `[::]`. Windows `netstat` lists no LISTENING socket for any of them (finding 1) | pass |
| S13 | `stop redis`, rerun the script | FAIL services/health/redis, exit non-zero | `FAIL services: expected [grafana jaeger otel-collector postgres prometheus redis redpanda ], running [grafana jaeger otel-collector postgres prometheus redpanda ]`; `FAIL health: not healthy: redis=absent`; `FAIL redis: redis-cli ping: service "redis" is not running`; the other 8 PASS; exit 1, 11.9 s | pass |
| S14 | `start redis`, rerun | 11 PASS | Redis healthy 5.7 s after `start`; 11 PASS, exit 0, 19.1 s | pass |
| S15 | Port held by a Windows process (scratch port 3199) | (systems entry: `up` fails with "port is already allocated") | `docker run -p 127.0.0.1:3199:6379` exit 0 while Python held `0.0.0.0:3199`; `127.0.0.1:3199` still answered `WINDOWS-PY`, not Redis (finding 1) | finding |
| S16 | `down` without `-v` | no containers, volumes kept, ports free | 7 containers and `llmgw_default` removed; `llmgw_grafana-data`, `_postgres-data`, `_prometheus-data`, `_redpanda-data` kept; 5440, 6390, 19092, 4317, 4318, 16686, 9090, 3101 closed; `candidatus-*` containers still up, the Next.js server still on 3001 | pass |

Memory (S11), `docker stats` / limit, first sample right after S10 and second before `down`:

| Service | Sample 1 | Sample 2 | Limit |
|---|---|---|---|
| grafana | 293.8 MiB (76.5%) | 263.4 MiB | 384 MiB |
| redpanda | 192.9 MiB | 192.9 MiB | 1.5 GiB |
| prometheus | 42.3 MiB | 45.9 MiB | 512 MiB |
| postgres | 25.4 MiB | 25.7 MiB | 512 MiB |
| otel-collector | 24.1 MiB | 27.6 MiB | 256 MiB |
| jaeger | 17.7 MiB | 17.9 MiB | 1 GiB |
| redis | 6.1 MiB | 7.3 MiB | 512 MiB |
| total | 602 MiB | 581 MiB | |

Grafana cgroup (inside the container): `memory.current` 400244736 B (381.7 MiB, 99.4% of `memory.max` 402653184), `anon` 276.3 MiB, `file` 93.1 MiB (85.5 MiB inactive), `memory.events` `max 363`, `oom_kill 0`. Second sample: `memory.current` 351.3 MiB, `anon` 245.7 MiB, still `max 363`, `oom_kill 0`. `OOMKilled=false` and 0 restarts on every container.

## Findings

1. **A host port held by a Windows process does not fail `up` on Docker Desktop. The container's port then answers nothing on the host** (medium, systems-entry correction, gotcha candidate). The systems entry's failure behaviour says "A host port already taken: `up` fails with Docker's 'port is already allocated'". That does not hold here when a Windows process holds the port. Evidence (S15, scratch port 3199):
   - With `python listener.py` bound to `0.0.0.0:3199` (netstat `TCP 0.0.0.0:3199 LISTENING 19528`), `docker run --rm -d --name llmgw-smoke-portprobe -p 127.0.0.1:3199:6379 redis:8.10.3-trixie` exited 0.
   - A connection to `127.0.0.1:3199` that sent `PING` then got `b'WINDOWS-PY\n'` from the Python listener, not Redis.
   - Docker Desktop leaves no Windows LISTENING socket for any published port. With the stack up, `netstat -ano` showed only `TIME_WAIT` client sockets to 5440, 6390, 16686 and 3101.

   Consequence today: the default stack on 3001 would have come up `healthy`, because the healthchecks run inside the container. `http://127.0.0.1:3001` would then have served the other project's Next.js app, and only `stack-check.sh`'s `grafana` check or a human would notice. I did not send a request to that server to confirm the script's FAIL text, and I did not test a Docker-vs-Docker conflict, which Docker should still detect. Possible follow-ups for the implementer: a host-listener pre-check (`netstat`/`Get-NetTCPConnection` on Windows, `ss` on Linux) in `scripts/preflight.sh` or before `up`, plus the systems entry's failure bullet corrected.

2. **Grafana's Jaeger datasource cannot search, not only fail its health check** (low, extends the systems entry's Jaeger gotcha). The entry says "trace lookups by ID from Grafana are unaffected". That is true (S5: `/api/ds/query` by trace ID 200 with the frame). But a search returns `400` and `{"results":{"B":{"error":"request failed: 404 Not Found","errorSource":"downstream","status":500,"frames":[]}}}`:

   ```json
   {"queries":[{"refId":"B","datasource":{"uid":"jaeger","type":"jaeger"},"queryType":"search","service":"llmgw-stack-check"}]}
   ```

   The search is backed by the legacy `/api/services` and `/api/traces?service=`, which Jaeger 2.22.0 answers with 404 (S6). So Explore's Jaeger Search tab and its service list are unusable, and any OBS-02 panel that searches Jaeger through Grafana would be empty. The Jaeger UI's own search works, because it uses `/api/v3` (S6).

3. **Grafana runs at its 384m memory ceiling from the start** (low, numbers for OBS-02). `memory.current` was 99.4% of `memory.max` about one minute after start, and `memory.events max 363` shows reclaim at the limit, with `oom_kill 0`. `docker stats` shows 263-294 MiB because it excludes inactive page cache. The real pressure figure is anonymous memory: 276.3 MiB at peak (the systems entry recorded about 255 MiB), which leaves about 108 MiB for dashboards, plugins and query load. No OOM in this run. When OBS-02 adds dashboards, watch `oom_kill` in `memory.events` and `OOMKilled`.

4. **Spec text out of step with the U6 fallback** (info, for the implementer). `docs/specs/FND-02-local-stack.md`:
   - section 8 check 11 (line 466) still requires `/uid/jaeger/health` to return `"status":"OK"`, while the script, per U6's fallback, checks only that the datasource exists;
   - section 4's address book (line 143) gives Jaeger's query API as `/api/services`, which 404s on 2.22.0;
   - the spec records no settlement of U6 (only its definition at line 674).

   The script and systems entry are right; the spec lines are stale.

5. **`stack-check.sh` under `bash -x` reports false FAILs** (info, harness, possible gotcha). `bash -x tools/smoke/stack-check.sh` printed `FAIL services: expected [++ docker compose -f deploy/compose.yaml config --services grafana …], running [++ docker compose … ps --services --status running grafana …]` and `FAIL redis: redis-cli ping: ++ docker compose … exec -T redis redis-cli ping PONG`. The script captures `2>&1` inside its command substitutions, so xtrace lines become part of the compared values. Normal runs are unaffected. To debug the script, send xtrace elsewhere (`BASH_XTRACEFD`) rather than using plain `-x`.

## What this run cannot show

- Anything over HTTP of ours: no gateway-app, agent-app or mock-provider ran. FND-02 has no API, so no Redis keys, ledger rows or GenAI spans of the gateway exist yet. The trace and metric paths were proven with one synthetic span and one gauge per run, not with an instrumented app or under load.
- Real provider latency and errors: no provider, real or mock, was called.
- Default Grafana port 3001: the stack ran with 3101 because of finding 1. Every other port used its default. That the default binding works was shown by the implementer's runs and `StackContractTest`, not here.
- Multi-replica and multi-project effects: one stack, one Redpanda node. The worktree-isolation criterion (a second project at +100 ports) was not rerun.
- Load, memory growth over hours and Prometheus retention: idle stack, minutes of uptime. Grafana memory with real dashboards (OBS-02) is unknown.
- Linux and CI networking: Docker Desktop on Windows only. Finding 1 is specific to Docker Desktop's port forwarding. CI (`stack.yml`, Ubuntu) was not run.
- Persistence across `stop`/`up` (criterion `fnd02_check`): not rerun. Volume reuse was seen only as Postgres skipping initialization at `up`.

## Revision (2026-11-03)

What the FND-02 fix round did with each finding (spec Log, "Review and fix round"). The spec line numbers quoted in finding 4 are those of the spec before its closing edit.

1. Grafana's default port moved from 3001 to 3301; `scripts/preflight.sh` warns about stack ports another process listens on while the project is down; the systems entry's failure bullet distinguishes a container conflict from a Windows process; new gotcha [docker-desktop-publishes-ports-held-by-windows-processes](../gotchas/docker-desktop-publishes-ports-held-by-windows-processes.md).
2. Added to [jaeger-2-22-legacy-search-api-404-breaks-grafana-health](../gotchas/jaeger-2-22-legacy-search-api-404-breaks-grafana-health.md) and the systems entry: OBS-02 dashboards must not rely on Jaeger search through Grafana.
3. Grafana's memory limit raised to 512m.
4. Spec section 4 Jaeger row, section 8 check 11 and the U6 settlement fixed at closing.
5. New gotcha [bash-xtrace-pollutes-captured-stderr](../gotchas/bash-xtrace-pollutes-captured-stderr.md); `stack-check.sh` says to debug with `BASH_XTRACEFD`.

The script gained a twelfth check, `grafana-host`. The rerun after the fix round (implementer, default ports with Grafana on 3301) gave 12/12 PASS; it was not a `gateway-tester` run.
