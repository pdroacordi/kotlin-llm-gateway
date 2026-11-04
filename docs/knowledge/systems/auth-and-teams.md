---
id: systems/auth-and-teams
type: system
title: Auth and teams: team keys, the admin key, one auth filter, an HMAC-hashed key store with a Caffeine cache
status: active
tags: [auth, teams, keys, admin, postgres, r2dbc, flyway, caffeine, security]
related: [systems/request-context-and-errors, systems/provider-adapters, systems/local-stack, patterns/port-fake-proved-by-a-shared-contract-test, patterns/request-context-coroutine-element, patterns/one-error-funnel, bugs/team-store-timeout-resumes-on-default-executor, bugs/started-test-contexts-dispose-global-reactor-resources, bugs/gw-04-tests-pass-without-the-behaviour-they-name, bugs/unknown-route-message-echoes-the-path-into-the-debug-log, gotchas/spring-jackson-encoder-logs-the-value-at-debug, gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux, gotchas/reactor-netty-answers-an-oversized-header-with-an-empty-431, runs/2026-11-04-smoke-teams-and-keys]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/TeamStorePort.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/TeamAuthenticator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/OperatorAuthenticator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/TeamAdminService.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/KeyHasher.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/team/BearerCredential.kt
  - gateway-infra/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/infra/postgres/PostgresTeamStore.kt
  - gateway-infra/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/infra/cache/CachingTeamStore.kt
  - gateway-infra/src/main/resources/db/migration/gateway/V1__teams_and_keys.sql
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/AuthWebFilter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/AuthConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/AuthProperties.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/auth/PublishedSecrets.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/admin/AdminHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/TeamStoreConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/TeamStoreWarmup.kt
---

## Summary

A product team calls `/v1/**` with `Authorization: Bearer llmgw_...`, the header every OpenAI SDK sends, and each such request resolves to exactly one team before any other stage. A platform operator manages teams and keys under `/admin/v1` with one admin key from the environment. A key is shown once and stored only as an HMAC digest in Postgres; a Caffeine cache keeps the hot path off the network, and an outage fails closed.

## Contract (never break)

1. Every `/v1` request resolves to exactly one team, and every `/admin` request to the admin key, before any other stage; nothing but `Authorization` is a credential. Team keys are stored as HMAC digests and shown once. `AuthWebFilterTest`, `AuthRouteTest`, `TeamContextPropagationTest`, `TeamKeysEndToEndTest`.
2. Scope is matched with `PathPattern` on the decoded path, the router's own matcher, never a prefix of the raw path: `/v%31/chat/completions` and `/v1;a=b/chat/completions` get 401 with no key. `AuthRouteTest`.
3. A route outside `/v1/**`, `/admin/**` or the exact open list fails the build. `AuthScopeTest`.
4. Unknown, revoked, expired, malformed and doubled credentials give one 401 `invalid_api_key`, equal bar the request ID, with no provider call (ADR-GW-04-004, -017). `AuthRouteTest`.
5. The two credentials never cross: a team key on `/admin` and the admin key on `/v1` are each 401 `invalid_api_key`, never 403, which would confirm a real key. `AuthRouteTest`, `AdminRouteTest`.
6. A store outage is never a 401: an unexpired cached key keeps serving, any other key gets 503 `team_store_unavailable` within `store-timeout`. `CachedKeyOutageRouteTest`, `StoreOutageIntegrationTest`, `PostgresTeamStoreFailureIntegrationTest`.
7. Revocation and expiry are judged in core on every request, cache hit or not. A revoke holds at once on its replica and within `cache.ttl` on any other. `TeamAuthenticatorTest`, `RevocationLagTest`, `CachingTeamStoreTest`.
8. A plain key, a digest, `Authorization`, the HMAC secret and the admin key are never recorded. The create response is written as bytes, because `JacksonJsonEncoder` logs what it encodes. `TeamKeyNoLeakIntegrationTest`, `NoLeakIntegrationTest`, `PathNoLeakTest`, `AdminRouteTest`.
9. A blank, short or equal secret stops the start naming only the property, and a published `local` value past loopback stops it too (ADR-GW-04-008, -019). `StartupFailureTest`, `PublishedSecretsTest`.
10. `/actuator/health` stays open and UP with Postgres down, so a load balancer keeps replicas that can serve cached keys (ADR-GW-04-005). `StoreOutageIntegrationTest`.
11. Nothing here blocks an event loop, including the first lookup on a cold pool. `PostgresColdStartBlockHoundTest`, `TeamStoreBlockHoundIntegrationTest`, on Linux, where a `/dev/urandom` read can trip BlockHound.

## How it works

- **Filter** (`AuthWebFilter.kt:24`, order `HIGHEST_PRECEDENCE + 1`, right after `RequestContextWebFilter`): `/v1/**` goes to `TeamAuthenticator`, `/admin/**` to `OperatorAuthenticator`, anything else straight on. A rejection raises `GatewayErrorException`, so a 401 or 503 reaches the one mapper with an `x-request-id`. The body is never read before auth.
- **Team on the request** (ADR-022): `withContext(TeamContext(team) + MDCContext(...))` adds a non-null element and MDC `teamId`. `TeamContext` exists only on an authenticated `/v1` request, so its presence is the proof. `ChatHandler` reads it with `checkNotNull`: a `/v1` route the filter missed is a 500, never an anonymous call. Core takes `TeamId` as a parameter and `ChatCall.team` carries it; adapters ignore it.
- **Parsing** (`BearerCredential.kt:21`): one `Authorization` line, scheme `Bearer` ignoring case. Absent, another scheme or an empty token is `missing_api_key`; more than one line, or a token not matching `TeamKeyFormat`, is `invalid_api_key` with no digest and no store call.
- **Key format:** `llmgw_` plus 43 characters of unpadded base64url, 256 bits from a dedicated strength-256 DRBG (`AuthConfiguration.kt:22`). No checksum (ADR-GW-04-002). `key_hint` is the first 10 characters, 24 of the 256 bits.
- **Hash** (`KeyHasher.kt:28`): HMAC-SHA-256 with the environment secret. One initialised `Mac` prototype, `clone()` per digest: `Mac` is not thread-safe, and `getInstance` per call would run a provider lookup on an event loop. `OperatorAuthenticator` compares digests, so the admin key's length leaks nothing.
- **Store:** `TeamStorePort` is the one port. `PostgresTeamStore` (`PostgresTeamStore.kt:44`) is the only user of `DatabaseClient`; every write is one statement (`ON CONFLICT ... DO NOTHING RETURNING`, `COALESCE(revoked_at, $1)`), so there is no transaction and no read-then-write race. `findKey` returns revoked and expired rows, and core judges them.
- **Tables** ([er-teams-and-keys](../../diagrams/er-teams-and-keys.md)): `teams` and `team_keys`, from `V1__teams_and_keys.sql`. Rows are never deleted. Key names are unique among unrevoked keys of a team, by a partial index. IDs, times and digests come from core; the database generates nothing.
- **Cache** (`CachingTeamStore.kt:30`, `TeamStorePort by delegate`): two Caffeine caches keyed by digest, positive and negative, so random tokens cannot evict good entries. `revokeKey` drops the entry on this replica, `createKey` drops the negative one. A `Failed` result is never cached.
- **Admin API** (`AdminRoutes`, `AdminHandler`, `AdminRequestReader`, `AdminResponseWriter`): five routes, GW-04 section 4. Unknown body members are refused, because `expire_at` would otherwise create a key that never expires. Keyset paging on UUIDv7 in OpenAI's list shape. `Cache-Control: no-store` on every response, rejections included.
- **Startup:** `AuthConfiguration.keyHasher` validates both secrets in a bean factory, before the server binds, because Boot's binder may print a value on a constructor failure. Flyway runs `V1` over JDBC, the only JDBC use. `TeamStoreWarmup` then does one lookup, retrying, so a first request pays no class loading.
- **Boundary:** `TeamStoreConfiguration` is the only `main` code naming `.gateway.infra` (ARCH-12).
- **Tests:** `InMemoryTeamStore` in `gateway-core`'s `testFixtures`, wired as a scanned `@Primary` fake, keeps `./gradlew test` Docker-free. `TeamStoreContract` runs against both it and real Postgres. `PostgresBackedTest` turns the real store on with `llmgw.test.team-store=postgres`.

## Failure behaviour

**Fail closed.** An unverified key must not spend a provider key.

| Postgres | Behaviour |
|---|---|
| Down or refused at runtime | Cached keys pass until their TTL ends; any other key and every admin route get 503 `team_store_unavailable` |
| Slow or hung | Same, after at most `store-timeout` (1 s). Smoke: 20 concurrent cold keys, max 1024 ms |
| Pool exhausted | Same, after `max-acquire-time` (500 ms) |
| Down at startup | The start fails at Flyway; a replica that cannot confirm its schema takes no traffic. A hung one fails after `connectTimeout` 5 s and `socketTimeout` 60 s |
| Schema or SQL bug | 500 `internal_error`: only connection, pool and timeout failures are `Unavailable` |

- The deadline is `Mono.timeout` around acquire and query together, not `withTimeoutOrNull`, which resumed on `DefaultExecutor` ([bug](../bugs/team-store-timeout-resumes-on-default-executor.md)).
- No retry in the gateway; SDKs retry a 503. A client disconnect cancels the query, and the connection returns to the pool.
- The driver's message is not logged: the adapter logs the exception class and SQLSTATE at WARN.
- After `cache.ttl` of a full outage, all traffic is 503. OPS-03 decides a readiness group.
- A lost 201 loses the plain key: the operator revokes that key and creates another.

**Accepted risks.** A flood of well-formed random tokens misses both caches and can make uncached keys 503, while cached keys keep working; per-source limiting belongs at the edge proxy (OPS-03). `local` holds a public admin key and DNS rebinding reaches loopback, so a web page could call the admin API of a `local` gateway; it reaches only the mock.

## Configuration

`LLMGW_KEY_HMAC_SECRET` and `LLMGW_ADMIN_KEY` are required unless `local` supplies its public fakes. Generate with `openssl rand -base64 32`.

| Property | Default | Note |
|---|---|---|
| `llmgw.auth.hmac-secret` | `${LLMGW_KEY_HMAC_SECRET:}` | 32 to 512 visible ASCII characters, used as bytes. Losing it invalidates every key |
| `llmgw.auth.admin-key` | `${LLMGW_ADMIN_KEY:}` | Same bounds; must differ from the HMAC secret |
| `llmgw.auth.store-timeout` | `1s` | One store operation. Positive, at most 10 s |
| `llmgw.auth.warmup-timeout` | `10s` | The whole warm-up. Positive, at most 2 m |
| `llmgw.auth.cache.ttl` | `30s` | The revocation lag across replicas. Positive, at most 5 m |
| `llmgw.auth.cache.max-size` | `10000` | Entries |
| `llmgw.auth.cache.negative-ttl` | `5s` | Positive, at most 1 m |
| `llmgw.auth.cache.negative-max-size` | `10000` | Entries |
| `spring.r2dbc.url` | from `LLMGW_POSTGRES_HOST`, `_PORT`, `_DB` | Defaults are the compose values |
| `spring.r2dbc.pool.*` | 2 to 10 connections, `max-acquire-time` 500ms, `max-create-connection-time` 2s, `max-validation-time` 250ms, `validation-depth` remote | ADR-GW-04-015: the library sets no limit by default |
| `spring.flyway.url` | the same variables, `connectTimeout=5`, `socketTimeout=${LLMGW_FLYWAY_SOCKET_TIMEOUT:60}` | Seconds; `socketTimeout` also bounds each migration statement |
| `management.health.r2dbc.enabled` | `false` | Contract 10 |

## Decisions

- Cross-cutting: [ADR-021](../../DECISIONS.md#adr-021) (R2DBC with `DatabaseClient`, Flyway over JDBC), [ADR-022](../../DECISIONS.md#adr-022) (caller identity at the edge).
- [GW-04 spec](../../specs/GW-04-teams-and-api-keys.md): ADR-GW-04-001 (persistence), -002 (key format), -003 (one filter, no Spring Security), -004 (401s), -005 (fail closed), -006 (cache), -007 (admin API), -008 (secrets at startup), -009 (team on the request), -010 (test strategy), -011 (port shape), -012 (HMAC in core), -013 (tables), -014 (no last-used), -015 (pool), -016 (`local`), -017 (two credentials), -018 (stopgap removed), -019 (published secrets), -020 (no path in a message).

## Gotchas

- `secret_version` is written, but reading two versions is not built: the HMAC secret cannot rotate without reissuing every key (GW-12).
- A non-loopback bind is not refused: authentication is the control. TLS is the edge proxy's, so a key on a plain-HTTP bind travels in clear (ADR-GW-04-018).
- An empty 400 or 431 from Reactor Netty never reaches this filter ([control characters](../gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux.md), [oversized headers](../gotchas/reactor-netty-answers-an-oversized-header-with-an-empty-431.md)). Its WARN line holds the request path.
- After a store miss the continuation resumes on the driver's `reactor-tcp-nio` thread, an event loop too: the no-blocking rule holds there.
- r2dbc-postgresql draws a SCRAM nonce from a default `SecureRandom` on each new connection, so `ScramNonceBlockHoundIntegration` allows that one method (GW-04 Log, S4).
- A test that closes a fully started context goes through `isolatedGateway()` ([bug](../bugs/started-test-contexts-dispose-global-reactor-resources.md)).
- A key pasted into a URL still has routes left: [bug](../bugs/unknown-route-message-echoes-the-path-into-the-debug-log.md), What is left.
