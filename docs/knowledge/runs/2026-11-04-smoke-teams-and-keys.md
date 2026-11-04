---
id: runs/2026-11-04-smoke-teams-and-keys
type: run
kind: smoke
title: Teams and API keys end to end against mock-provider and compose Postgres - GW-04 section 8 scenarios, admin API, auth scope, two replicas, Postgres faults, bad starts, GW-03 closing fixes
status: active
build: d89c2a6 + uncommitted GW-04
area_tested: "GW-04 auth filter, admin API, V1 migration, key cache and Postgres store; tools/smoke/team-key.sh and auth.sh first real run; chat-completions.sh, streaming.sh, anthropic.sh with team keys; GW-03 parallel_tool_calls and thinking_blocks fixes"
verdict: pass-with-findings
tags: [smoke, gw-04, auth, admin, postgres, flyway, cache, revocation, startup, regression]
related: [systems/request-context-and-errors, systems/local-stack, systems/provider-adapters, runs/2026-11-04-smoke-anthropic-adapter, runs/2026-11-04-smoke-sse-streaming, gotchas/spring-jackson-encoder-logs-the-value-at-debug, gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/team-key.sh
  - tools/smoke/auth.sh
  - tools/smoke/anthropic.sh
  - tools/smoke/chat-completions.sh
  - tools/smoke/streaming.sh
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/AuthWebFilter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/admin/AdminHandler.kt
  - gateway-infra/src/main/resources/db/migration/gateway/V1__teams_and_keys.sql
---

## Verdict

`pass-with-findings`. 633 checks passed and 2 failed: 352 passed in the four scripts with 0 failed, and 281 passed by hand with 2 failed. One failure is a low defect (finding 1): a key-shaped token in the path of an unknown route reaches a DEBUG log line. The other is a case where the brief asked for more than the spec does (finding 2), and no route is reached. No unauthenticated request reached a route or the provider, no good key got a 401 during an outage, and every bad start stopped with the value kept out of the log.

## What was run

- **Stack:** only `postgres` from compose (`postgres:18.6-trixie`), started by this run. `docker compose ps -a` was empty before. The `llmgw_postgres-data` volume existed, and database `llmgw` held no tables. `stack-check.sh` was not run, because GW-04 uses no Redis, broker or span and the other six services stayed down.
- **Runtime:** Windows 11, Java 25.0.4.1. `bootJar` of both apps, rebuilt from the tree at the start of the run and run with `java -jar`.
- **mock-provider:** defaults, reset by each script. Every route pointed at it.
- **No real provider:** `ANTHROPIC_BASE_URL`, `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` were removed from every start; root `.env` was not read; `live-openai.sh` was not run. `api.openai.com` and `api.anthropic.com` appear 0 times in all 27 log files.
- **Tools:** the scripts, plus a stdlib Python client over raw sockets, so paths reach the server unnormalised. Keys stayed in process memory, and evidence was redacted before printing.
- **Beside this run:** four `candidatus-*` containers, untouched. Treat latency as noisy.

| Start | Settings | Used for |
|---|---|---|
| 1 | `local`, `web` DEBUG | first start, V1 |
| 2 | request, first byte and idle 2 s on both providers, client write 1 s, `web` DEBUG | second start, `HANG=1` scripts, GW-03 fixes |
| 3 | `local`, `web` DEBUG | `auth.sh` with `PG_FAULT=1` and `GATEWAY_LOG`, contract battery |
| 4 | two replicas on 8080 and 8082, TTL 3 s | restart, revocation lag |
| 5 | two replicas, default TTL 30 s, then one | lag at 30 s, Postgres stopped and hung |
| 6 | `local`, INFO | latency, log canaries at INFO, forwarding |
| 18 more on 18080 | see the start table | bad starts and their controls |

## Results

Script counts, on the final run of each:

| Script | PASS | SKIP | FAIL | Exit | Last run |
|---|---|---|---|---|---|
| `HANG=1 chat-completions.sh` with `GATEWAY_LOG` | 60 | 1 (7, manual stopped mock) | 0 | 0 | 61; the non-loopback scenario 12 was deleted by GW-04 |
| `HANG=1 streaming.sh` with `GATEWAY_LOG` | 68 | 0 | 0 | 0 | 68 |
| `HANG=1 anthropic.sh` | 184 | 1 (9, OpenAI-only start) | 0 | 0 | 184 |
| `PG_FAULT=1 auth.sh` with `GATEWAY_LOG`, `web` DEBUG | 40 | 2 (12, 13, run by hand below) | 0 | 0 | first run |

`team-key.sh` worked first time in all four scripts. Without `GATEWAY_LOG`, the first runs gave 56 and 64 PASS with 2 SKIP each, 0 FAIL.

Hand checks:

| Block | PASS | FAIL |
|---|---|---|
| GW-03 fixes | 6 | 0 |
| Admin API and auth contract, path battery | 166 | 1 (finding 2) |
| Restart, replicas, Postgres stopped and hung | 76 | 0 |
| Starts | 18 | 0 |
| Rows, dump, logs | 10 | 1 (finding 1) |
| Encoded paths with a key, forwarding | 5 | 0 |

### Scenarios

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| First start | V1 runs | `Migrating schema "public" to version "1 - teams and keys"`; history row `1, V1__teams_and_keys.sql, true` | pass |
| Second start | V1 not rerun | `Schema "public" is up to date. No migration necessary.`; 1 history row | pass |
| GW-03 `"parallel_tool_calls":"x"` | 400 | 400 `unsupported_value`, `param: parallel_tool_calls`, with and without `tools`, and for `1`; `lastSeq` unchanged. `false` still 200 | pass, by hand (finding 3) |
| GW-03 `"llmgw":{"thinking_blocks":null}` on an assistant message | counts as absent | 200; journal body equal to the same request with no `llmgw` | pass, by hand (finding 3) |
| Create team and key | 201, key shown once | 201; 49 characters matching the format; hint is the first 10; `no-store`; no digest | pass |
| List | no key, no digest | `key_hint` only; `has_more`, `first_id`, `last_id` and `after` paging; empty page has `null` IDs | pass |
| Validation and 404, 409 rows | section 4 | every row, including `expire_at` typo 400 `param: null`; offset `expires_at` stored as UTC | pass |
| Revoke | 401 at once on this replica | next call 401; second revoke keeps the first `revoked_at`; name reusable; under another team 404 `key_not_found` | pass |
| `missing_api_key` | 401, `WWW-Authenticate: Bearer` | none, `Basic`, `Bearer `, `Bearer`, empty header, `x-api-key`, `api-key`, `?access_token=` | pass |
| Identical 401 | body and headers equal bar the ID | unknown, revoked, expired, malformed, two lines, two equal lines, comma form: equal bytes, `Bearer error="invalid_token"` | pass |
| Cross credentials | 401 `invalid_api_key` | team key on `/admin`, admin key on `/v1` | pass |
| `/actuator/health` | open | 200 UP, with no key, and with Postgres stopped or hung | pass |
| 40 unauthenticated `/v1` and `/admin` forms | 401 | 39 gave 401 `missing_api_key`: `/v1//chat/completions`, trailing slash, `/v%31/...`, `/%76%31/...`, `;a=b`, wrong method, unknown paths, preflight; none has `Access-Control-*`. One gave 404 (finding 2) | 39 pass, 1 fail |
| Same with a key | 404, 405, 200 | 405, 404; preflight 200 with no `Access-Control-*`; `/v%31/...` and `;a=b` 200 | pass |
| No provider call on any of the above | `lastSeq` unchanged | unchanged | pass |
| Other actuator paths | brief: 401 | 404 for `/actuator/env`, `beans`, `heapdump` and others (finding 2) | spec holds |
| `x-api-key`, `api-key`, query on a keyed call | never forwarded | absent from the journal; team key absent; fingerprint `b9a55896` is `mock-local-key`'s | pass |
| MDC and audit | team on the request; IDs only | `[requestId] [teamId]` on chat lines, `[]` on 401; `Team created`, `Key created`, `Key revoked` carry IDs only | pass |
| Key survives a restart (12) | 200 | 200; a revoked key stays 401 | pass |
| Two replicas, TTL 3 s (13) | refused within the TTL of the other's cache write | 7 trials, both directions: 3.03 to 3.08 s after that write, 1.03 to 3.06 s after the revoke; only 200 then 401 | pass |
| Two replicas, default TTL | at most 30 s | 29.99 s after the revoke, 30.10 s after the write | pass |
| Postgres stopped: cached key | 200 until its TTL | 200 until 30.16 s after the cache write, then 503; polled every 250 ms | pass |
| Postgres stopped: uncached key | 503, never 401 | 105 of 105 `team_store_unavailable`, p50 5.4 ms, max 32.2 ms; request ID in the message; no driver text | pass |
| Postgres stopped: other | section 5 | cached revoked 401, malformed 401, negative-cached 401, uncached unknown 503, all five admin routes 503 with `no-store`, health 200 | pass |
| Recovery after `compose start` | 200 | first request 200, 0.03 s after `start` returned; no 401 | pass |
| Postgres hung, 20 concurrent cold keys | 503 within 1.5 s | all 503, p50 1016 ms, max 1024 ms; cached key 200; recovery on the first request after `unpause` | pass |
| Logs | no key, no digest | see finding 1 for the one hit: 13 797 lines in 27 files; 654 digests in hex and base64: 0; all secrets: 0; `Authorization` only in the fixed error message and a Netty header name | fail, finding 1 |
| Rows, read-only | digest only | digest equals HMAC of a fresh key; 32 bytes on all rows; all hints 10 characters; `pg_dump` has 0 keys; Postgres log has 0 keys and digests | pass |

### Starts

Each refused start exited 1, never bound its port, and logged no value. "Missing" means the variable was absent from the environment.

| Start | Result | Message or time |
|---|---|---|
| HMAC secret missing, blank or short; both missing | refused, 3.6 s | `llmgw.auth.hmac-secret must be 32 to 512 visible ASCII characters: fix the LLMGW_KEY_HMAC_SECRET environment variable.` |
| Admin key missing or short | refused, 3.6 s | the same for `llmgw.auth.admin-key` and `LLMGW_ADMIN_KEY` |
| The two equal | refused, 3.7 s | `llmgw.auth.admin-key must differ from llmgw.auth.hmac-secret.` |
| `local` on `0.0.0.0`, `::`, blank address | refused, 3.6 s | `llmgw.auth.hmac-secret holds the published value of the local profile and server.address is not loopback` |
| Only the published admin key on `0.0.0.0`, no profile | refused, 3.8 s | the same for `llmgw.auth.admin-key` |
| Only the published HMAC secret on `0.0.0.0`, no profile | refused, 3.6 s | as `local` |
| `local` on `127.0.0.2`, on `localhost`; own secrets with no profile | started, 4.3 to 4.4 s | controls |
| Wrong Postgres password | refused, 3.7 s | Flyway: `password authentication failed for user "llmgw"`; password not logged |
| Postgres stopped | refused, 3.7 s | `Connection refused` at Flyway |
| Postgres paused | refused, 69.8 s | `SocketTimeoutException: Read timed out`; `connectTimeout` 5 s and `socketTimeout` 60 s, as stated |

### Latency

INFO logging, keep-alive client, sequential, `mock-chat`, 2000 interleaved samples per side, after 3000 warm-up calls. Two runs:

| Metric | Run 1 | Run 2 | Target |
|---|---|---|---|
| Direct to mock, p50 | 0.168 ms | 0.135 ms | |
| Through the gateway, cached key, p50, p95, p99 | 0.624, 1.407, 2.113 ms | 0.458, 0.835, 1.338 ms | |
| Added, cached, p50 | 0.456 ms | 0.323 ms | under 1.00 ms (0.90 baseline + 0.1): met |
| First use of a key, p50, p95, p99 (n=300) | 2.753, 4.382, 5.182 ms | 1.370, 2.824, 4.234 ms | |
| Miss over cached, p50 | 1.822 ms | 0.856 ms | under 5 ms: met |

The added figure is below the GW-03 baseline of 0.90 ms, but that is no proof auth costs nothing: the two runs differ in JIT state and machine load, and GW-03's client may differ too. `auth.sh` scenario 11 reports 2.30 and 7.26 ms because `curl` opens a new connection per call; do not compare it with this table.

## Findings

### 1. A key in the path of an unknown route reaches a DEBUG log line (low)

Acceptance says a plain key appears in no log line at DEBUG with the `web` group on. `unknown_url` quotes the path, and `JacksonJsonEncoder` logs that envelope at DEBUG.

- **Reproduction:** start with `LOGGING_LEVEL_WEB=debug`; `GET /nope/llmgw_<43 characters>` with no credential; grep the log.
- **Evidence:** status 404, and `g3-auth.log` lines 1155 and 1190: `JacksonJsonEncoder : Encoding [OpenAiErrorEnvelope(error=Body(message=Unknown request URL: GET /nope/<canary> (truncated)...]`. The token was a fake of the key's shape.
- **Limits:** a client must put a key in a URL path, on a path that is not 401, with DEBUG on. Query strings are not quoted. `/v1/<token>` and `/admin/v1/<token>` with no key are 401 and quote nothing. At INFO the count was 0. A keyed request to an unknown `/v1` path takes the same 404 route, so it should behave alike; that was not sent with a canary.
- **Same shape as** [`spring-jackson-encoder-logs-the-value-at-debug`](../gotchas/spring-jackson-encoder-logs-the-value-at-debug.md), which GW-04 solved for the create response by writing bytes.

### 2. The brief's 401 list is wider than the spec (information, a decision for the orchestrator)

Neither case serves anything, and both follow section 4, but the brief said 401:

- **`/v1%2Fchat%2Fcompletions` with no key is 404 `unknown_url`.** This is the failed check. It decodes to one segment, so it matches neither `/v1/**` nor a route, and with a key it is 404 too, with `lastSeq` unchanged: no bypass. `/v1/chat%2Fcompletions` is 401, and with a key 404. Also 404 with no key, reaching no route: `/v1%2F/chat/completions`, `//v1/chat/completions`, `/V1/chat/completions`.
- **Other actuator paths are not 401.** `/actuator/env`, `beans`, `metrics`, `heapdump` and others are 404, which section 4 states for any other path. `/actuator`, `/actuator/health/liveness` and `/actuator/health/readiness` answer 200 with no key, and `AuthScopeTest` holds them in its open list. `/actuator` shows only health links, with hrefs built from the request `Host`.

### 3. `anthropic.sh` does not contain the two GW-03 checks (low, tooling)

The brief asks the script to confirm both fixes. It has no `parallel_tool_calls` check and no `thinking_blocks: null` check, so its 184 PASS say nothing about them. Both were confirmed by hand, on the mock with `mock-claude`, and the script was not edited in this run. Each is a two-line addition.

### 4. Observed, no change needed

- `Bearer  <key>` with two spaces gives 200: RFC 6750 allows `1*SP`. A tab gives `missing_api_key`.
- A header over 8192 bytes gives an empty 431 with no envelope and no `x-request-id`, from Reactor Netty, as a control character gives an empty 400. Its WARN line filters header values and the query string (`POST /v1/chat/completions?<filtered> HTTP/1.1`).
- After `unpause`, compose shows `unhealthy` for a few seconds until the next probe. The gateway served meanwhile.

## What this run cannot show

- Real provider latency and errors: every call went to the mock.
- More than two replicas, or replicas on different hosts and clocks; a load balancer in front.
- Concurrent starts racing on Flyway: the replicas started one after the other, on a migrated schema.
- Behaviour under load: a flood of random tokens, pool exhaustion past 20 concurrent cold keys, or a sustained outage with many cached keys. No k6 run.
- BlockHound: this run was on Windows, and the jar runs without it.
- Non-loopback binds with own secrets: not started, to keep the gateway off the LAN.
- `chat-completions.sh` scenario 7 and `anthropic.sh` scenario 9: skipped, manual, not repeated here.
- A stopped Postgres under a real network: `compose stop` closes sockets cleanly, and `pause` stands in for a hang.

## State left behind

- Compose: `llmgw-postgres-1` stopped with `docker compose stop`, so one exited container now exists where none did before. Volume kept. Other services never started.
- Postgres `llmgw`: schema V1 and `flyway_schema_history`; 16 teams and 656 keys, 14 of them revoked, all made by this run through the admin API, with names `smoke-*`, `p1-*`, `p2-*`, `p4-*` to `p7-*`. No row was deleted.
- Ports 8080, 8082, 8090 and 18080 free.
- Session scratchpad: `tmp1` still holds one throwaway header file with a key of a `local` team, because a hook blocked its removal. The key works only with the public `local` HMAC secret and this volume.
