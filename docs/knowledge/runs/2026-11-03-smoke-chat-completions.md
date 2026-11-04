---
id: runs/2026-11-03-smoke-chat-completions
type: run
kind: smoke
title: First request client to gateway-app to mock-provider - GW-01 section 8 scenarios, the full upstream error table, limits, cancellation, startup refusals
status: active
build: f060261 + uncommitted GW-01
area_tested: "GW-01 POST /v1/chat/completions, OpenAI adapter, LoopbackHostWebFilter, ExposureGuard, tools/smoke/chat-completions.sh"
verdict: pass-with-findings
tags: [smoke, gw-01, chat-completions, openai-compat, errors, cancellation, reactor-netty, logging]
related: [systems/provider-adapters, systems/mock-provider, systems/request-context-and-errors, runs/2026-11-03-smoke-mock-provider-and-errors, patterns/upstream-bytes-then-pure-classification, bugs/gateway-body-limit-on-functional-route-gives-500]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - tools/smoke/chat-completions.sh
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiRequestWriter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatRequestReader.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/security/LoopbackHostWebFilter.kt
---

## What was run

- **Stack:** no compose stack (GW-01 calls no Redis, Postgres or broker). Windows 11, Spring Boot 4.1.2, gateway on Java 25.0.4.1 under `bootRun`, OpenJDK 25 under `java -jar`. Ports 8080, 8081 and 8090 were free before the run.
- **mock-provider:** `./gradlew :mock-provider:bootRun` (PID 45304, killed for scenario 7, then PID 42988). Default 0 ms latency, `POST /__mock/reset` before each block, behaviour from per-model scenarios. No real provider was called; every run without the `local` profile also set `LLMGW_PROVIDERS_OPENAI_BASE_URL` to the mock.
- **gateway-app, five starts and ten refused starts, profile `local` unless noted:**

| Start | How | Settings | Used for |
|---|---|---|---|
| 1 | `bootRun`, PID 8708 | `io.github.pdroacordi.llmgateway` at DEBUG, default timeouts | script, sections A to K |
| 2 | `bootRun`, PID 22896 | same, `REQUEST_TIMEOUT=2s`, fake `OPENAI_API_KEY=sk-canary-shell-key` | script with `HANG=1`, sections L to N |
| 3 | `java -jar`, PID 44244 | `LOGGING_LEVEL_ROOT=DEBUG` | log audit beyond the spec |
| 3b | `java -jar`, PID 14492 | `org.springframework.http.codec` at TRACE | finding 5 |
| 4 | `java -jar`, PID 16004 | INFO; `MAX_RESPONSE_SIZE=2KB`, pool 2 connections, acquire 1 s, `REQUEST_TIMEOUT=6s` | latency, response limit, pool |
| - | `java -jar`, 10 runs | invalid configuration | startup refusals |

- **Tools:** curl and a stdlib Python client (raw sockets for Host, chunked and disconnect cases). Raw outputs are in the session scratchpad, not the repo.
- **Beside this run:** 9 short `eclipse-temurin:25-jdk` containers, started by another process between 21:47:11 and 21:48:39, overlapped the latency block (21:48:10 to 21:48:14). None was started by this run and none used 8080 or 8090. Treat the latency figures as noisy.

## Results

Check counts: script 61 PASS, 3 SKIP, 0 FAIL, exit 0 (first pass); 63 PASS, 2 SKIP, 0 FAIL, exit 0 (`HANG=1`). By hand 239 checks, 235 passed. The 4 others: 2 are finding 2, 1 is finding 1, 1 was a harness error (H5).

| # | Scenario | Expected | Observed | Result |
|---|---|---|---|---|
| S1 | Spec 1: health and bind | UP; `127.0.0.1:8080` only | UP; netstat `127.0.0.1:8080 LISTENING 8708`, no other listener | pass |
| A | Spec 2: completion | 200, section 4 shape, UUIDv7, `x-gateway-deployment: openai`, echo | 200; headers exactly `content-length, content-type, x-client-request-id, x-gateway-deployment, x-request-id`; member order as the spec; `usage` 7/16/23; client `x-request-id: client-chosen` replaced; body equal to the mock's direct body after normalising `id` and `created` | pass |
| A | Spec 3: correlation | one journal entry; fingerprint of `mock-local-key` | one entry; `x-client-request-id` = gateway ID; `credential {bearer, b9a55896}` = SHA-256[:8] of `mock-local-key`, with `Authorization: Bearer client-key` sent | pass |
| A | Client headers not forwarded | only the gateway's | journal headers exactly `accept, authorization, content-length, content-type, host, user-agent (ReactorNetty/1.3.8), x-client-request-id`; `OpenAI-Organization`, `OpenAI-Project`, `Cookie`, `X-Custom-Client`, `x-forwarded-for`, client `User-Agent` all absent | pass |
| B | Spec 4: pass-through | unknown members unchanged at every level | `x_future`, `seed` 9007199254740993, `temperature` 0.7, `top_p` 1.10, a 30-digit integer, null members, `x_msg`, `x_part`, unknown part types, `tools`, `response_format`, unicode, `stream` removed, model `weird/model name:1?x=y` verbatim | pass |
| B4, B5 | Absent `content` | stays absent | `{"role":"tool","tool_call_id":"c1"}` went upstream as `{"role":"tool","content":null,"tool_call_id":"c1"}` | finding 2 |
| C | Spec 5: upstream error table, 28 rows | section 4 status, `type`, `code`, `param`, exact message, headers | 28 of 28 exact: 400 (default, relayed code and param, sentence code dropped), 413, 422, 401, 403, 404 with and without `model_not_found`, 429 (`retry-after` 7 gives 7 and 7000; `retryAfterMs` 1500 gives 2 and 1500; none, 0 and garbage give no header), quota by `code`, by `type`, and all 5 quota set values, 500, 502, 409, 503 with and without `retry-after`, 504, 408. HTTP-date `retry-after` also passed | pass |
| C | Nothing leaks, no retry | no provider text or header; one upstream call | every row injected `message: canary-provider-message org-canary sk-canary-upstream` and `openai-organization`, `x-ratelimit-*`, `set-cookie`, `x-upstream-canary` headers: none in any response; response headers exactly `content-length, content-type, x-request-id` plus the row's own; one journal entry per call, `error_injected` | pass |
| C | Spec 6: `malformed`, `disconnect` | 502 `upstream_error` | both exact; journal outcomes `malformed`, `aborted` | pass |
| L | Spec 6: `hang`, 2 s timeout | 504 `upstream_timeout`, `client_cancelled` within 2 s | 504 at 2006 ms; journal `client_cancelled`, `durationMs` 2002, read 15 ms after the response; `ttftMs` 2500 gives 504 at 2017 ms; `ttftMs` 1500 gives 200 at 1523 ms | pass |
| K2 | Spec 7: stopped mock | 503 `provider_unavailable` | 5 of 5 exact, 1.7 to 24.8 ms; no `retry-after`; gateway health stays UP | pass |
| K4 | Spec 7: restart | 200, no gateway restart | gateway PID 8708 unchanged; first call 200 in 41 ms, journal seq 1 on the new mock | pass |
| K1 | Mock killed with a call in flight | No exact row in section 4; this run expected 502 from "connection closed mid-response" | 503 `provider_unavailable` | finding 1 |
| N | Spec 8: estimate | 200, `x-gateway-usage-estimated: true` | header `true`, usage 14/27/41 with 3 members; with provider usage (cached 3) body equals the mock's and the header is absent | pass |
| Log | Spec 9: no leak, DEBUG on our package | 0 hits | 2623 lines over starts 1 and 2: 0 hits for `canary-prompt`, `canary-provider-message`, `org-canary`, `sk-canary`, `mock-local-key`, `client-key`, client canaries, `Bearer`, `authorization`, completion text, `"messages"`, invalid body text, exponent literal. 59 ERROR lines, all with `[<id>]` | pass |
| Log | Root DEBUG, beyond the spec | no canary | 1673 lines, 0 hits for the same list; response bodies appear cut at 100 characters | pass, see finding 5 |
| D | Spec 10: route errors | section 4 rows, no upstream call | 13 validation rows exact with `param`; `stream: true` 400 `unsupported_value`; 9 `invalid_body` cases (empty, truncated, array, scalar, `null`, garbage, two values, `NaN`, comment); 415 for text/plain, none, form, `vnd.api+json`, unparsable; 405 and `allow: POST` for GET, PUT, DELETE, PATCH; `GET /v1/models` 404; `lastSeq` unchanged | pass |
| E | Host filter | foreign Host 403 with the envelope, no upstream call | 7 foreign values 403, `evil.example` body `{"error":{"message":"The request was rejected with HTTP status 403.","type":"invalid_request_error","param":null,"code":null}}`, `lastSeq` unchanged; `/actuator/health` also 403; 7 loopback spellings 200 including `[::1]:8080` and `LOCALHOST:8080`; two Host headers 403 in either order | pass |
| O | Spec 11: latency, informational | recorded | Table below | info |
| St | Spec 12 and refusals | exit non-zero, name the property, no bind, no key | 10 of 10 exit 1 in 1.8 to 3.0 s, no Netty start, 0 key hits: `0.0.0.0`, `::`, empty address; key unset, empty, spaces; `request-timeout` `0s`, `banana`; base URL with user info, `ftp` | pass |
| M | `local` key beats the shell | `mock-local-key` | fingerprint `b9a55896`, not `601b2509` of the shell value | pass |
| H1 | Body over 10 MB | 413 `request_too_large`, no ERROR, no upstream call | 10485761 B, +1 KB, 30 MB: exact; 0 ERROR lines; `lastSeq` unchanged; curl stops at about 10.9 MB of upload in 17 ms | pass |
| H2 | Boundary and order | limit passes; 415 first | 10485760 B and one byte less: 200; over the limit with `text/plain`: 415 | pass |
| H3 | Chunked over the limit | 413 | limit + 1: 413; 30 MB keep-alive x3 and curl: 413 | pass |
| H4 | Under the limit, grows when written | no 5xx | 2 million `1e-5` (10000071 B) grow past the mock's limit: 413 `upstream_rejected_request` in 1215 ms | pass, finding 7 |
| H5 | Chunked 30 MB with `Connection: close` | 413 | client saw a connection abort after 10.9 MB and no response | harness, finding 8 |
| F | Huge exponent | 400 past BigDecimal's range; no 5xx | `1e99999999999`, `1e2147483648`, `1e-2147483648` and signed variants: 400 `invalid_body`, literal not in the body. Up to `1e2147483647`: 200, forwarded as `1E+2147483647`, 3 to 24 ms, no expansion | pass, finding 6 |
| F | Long numbers and names | no 5xx | 1000-digit integer 200; 1001 digits 400; 2001-character decimal 400; 60000-character member name 400 | pass |
| G | Deep nesting | 400, no 5xx | `x` at depth 499 200, 500 `invalid_body`, in arrays and objects; every depth 10 to 519 scanned, plus 1100, 5000, 100000, 1000000: no 5xx; inside a content part 496 passes, 497 is 400 | pass |
| I | Client disconnect during `hang`, 300 s deadline | `client_cancelled` within 2 s | FIN 16 ms, RST 16 ms, slow `ttftMs` 19 ms after the close; 30 concurrent disconnects: 30 entries, `inFlight` 0, next call 200 | pass |
| J | 640 calls at concurrency 32, echo mode | no cross-talk | 640 of 640 200, each with its own prompt, 640 unique IDs, 0.20 s; 4 sampled IDs map to the right journal body | pass |
| P | Response over `max-response-size` (2 KB, mock sent 28938 B) | 502 `upstream_error` | exact; log `DataBufferLimitException` | pass |
| Q | Pool: 2 held, 9 waiting, queue 4, acquire 1 s | 503 `provider_unavailable` | 9 of 9 503: 5 refused at 12 to 17 ms, 4 at 1006 to 1020 ms; held calls 504 at 6014 ms; next call 200 | pass |

Latency, mock at 0 ms, median of three rounds of 50 calls after 200 warm-up calls, INFO level:

| Client | Direct p50 | Gateway p50 | Added p50 | Gateway p95 per round |
|---|---|---|---|---|
| Python, keep-alive | 0.40 ms | 3.06 ms | 2.66 ms | 3.5 to 7.7 ms |
| Python, new connection | 1.40 ms | 3.20 ms | 1.80 ms | 21 to 28 ms (direct 17 to 25 ms) |
| curl, 100 calls, DEBUG (script) | 3.11 and 3.28 ms | 5.85 and 6.16 ms | 2.74 and 2.88 ms | not measured |

## Findings

No finding breaks an acceptance criterion in section 3.

1. **A reset after the request was written is reported as "cannot be reached"** (low today; matters to RTE-02). A call was held by `hang` on an established connection, then `taskkill /F` killed the mock.
   - Request: `POST /v1/chat/completions {"model":"stop-hang",...}`, `x-request-id: 01a1a403-1c63-7b1d-8511-4a6886c25582`.
   - Response: 503 `provider_unavailable`, not 502 `upstream_error`.
   - Log: `Provider call failed: deployment=openai exception=...WebClientRequestException durationMs=1137`, caused by `java.net.SocketException: Connection reset`.
   - Cause: `OpenAiTransportFailures.classify` treats any `WebClientRequestException` without `PrematureCloseException` as `ProviderUnavailable`. Spec section 5 justifies that branch with "nothing was received: refused, DNS, TLS, pool", but here the request had been sent 1137 ms earlier.
   - So the same event gives 502 when the peer closes with FIN (`disconnect` fault) and 503 with RST. A client sees a retryable error either way. RTE-02 is the risk: `ProviderUnavailable` reads as "never reached", so a fallback may repeat a call the provider already received and bills. The journal entry died with the mock, so delivery rests on the 1137 ms the connection was held. Decide whether a reset after the write is `UpstreamError`, and name it in section 4.

2. **An absent `content` is sent upstream as `"content":null`** (low). `ChatRequestReader.content` maps absent and null to the same `null`, and `OpenAiRequestWriter.message` always sets the member, so absent becomes null and the following members move after it. Sent `{"role":"tool","tool_call_id":"c1"}`; the journal `body` holds `{"role":"tool","content":null,"tool_call_id":"c1"}`. Section 4 accepts absent, and AC 5 lists only unknown members, so no criterion fails, but it is the one member seen changing in transit. An assistant turn with `tool_calls` and no `content` takes the same path, which is GW-06's. `ChatRequestReaderTest` "content may be null or absent" checks acceptance, not what goes upstream.

3. **`Host: [::1` never reaches `LoopbackHostWebFilter`** (info, gotcha candidate). Reactor Netty answers `400`, `content-length: 0`, `connection: close`, no envelope, no `x-request-id`, and logs `WARN r.n.http.server.HttpServerOperations ... Decoding failed: REQUEST(decodeResult: failure(java.lang.NumberFormatException: For input string: ":1"))`. The request is refused, so nothing is exposed, but the filter's unclosed-bracket branch is reachable only from a unit test. Same class as finding 3 of [the previous run](2026-11-03-smoke-mock-provider-and-errors.md).

4. **A client disconnect leaves no line with the request ID; an RST leaves a WARN without one** (info, gotcha candidate). Request `01a1a401-f28c-73ef-ae6e-16fd4267e2c6` (cancelled, DEBUG on) has 0 gateway log lines. Each client RST logged `WARN reactor.netty.channel.FluxReceive : An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`: 16 lines for 16 RST disconnects, 0 for FIN. No request ID, so a call the provider may bill cannot be found from the gateway log alone. The upstream reset in finding 1 adds `WARN r.netty.http.client.HttpClientConnect ... The connection observed an error` with a stack trace and no ID. OBS-03 and COST-02 should note both.

5. **Spring's codec logger prints the response** (info, gotcha candidate). The spec's check, DEBUG on our package, is clean. Outside it, the response tree goes through `JacksonJsonEncoder`: with `org.springframework.http.codec` at DEBUG the line holds the first 100 characters (`Encoding [{"id":"chatcmpl-mock-...","model":"mock-cha (truncated)...]`, which stops before the content only because `id` is long); at TRACE the whole body, seen as `"content":"canary-prompt-P2"` in an `Encoding [...` line (echo mode, so a completion). Prompts, the key and provider messages stayed absent, because the request side uses `ByteArrayDecoder` and `ByteArrayEncoder` (`Read 85 bytes`). The CLAUDE.md rule on completion text therefore depends on that logger staying at INFO.

6. **Number spelling and sign change in transit** (info). `2e3` goes out as `2E+3`, `-0.0` as `0.0` (sign lost), `1e-5` as `0.00001` (documented), `1E+400` unchanged. Values stay equal, apart from the sign of zero. A literal inside `BigDecimal`'s range reaches the provider, so `1e2147483647` is the provider's to refuse; the mock accepted it. A 20-byte body cannot force an expansion: the longest of these took 24 ms.

7. **Worst-case body costs over a second** (info, for OPS-01). The 10 MB body of 2 million numbers took 1215 ms end to end, parsed and converted on an event loop, as section 9 predicted. The result is correct: the mock's 413 maps to the 413 row, with `upstream_rejected_request`.

8. **Harness and minor observations** (info).
   - H5 was the harness: a client that asks for `Connection: close` and keeps writing after the 413 gets a connection abort, 3 of 3. curl and keep-alive clients read the 413.
   - An absolute-form request line with a foreign authority and `Host: 127.0.0.1:8080` gives 200, and a request with no Host gives 200. Both follow the filter's design, and a rebinding browser sends neither.
   - A UTF-8 BOM before the object is accepted and not forwarded.
   - Duplicate members: the last wins (`{"model":"a","model":"mock-dup"}` went upstream as `mock-dup`).
   - `bootRun` of the mock listens on `0.0.0.0:8090` and `[::]:8090`, not loopback, and its journal keeps prompts. The local-stack rule covers compose ports only.
   - `tools/smoke/chat-completions.sh` omits upstream 403, 408, 413 and 422 and checks status and `code` only; section C covers those rows and the exact envelope.

**Against the previous run:** its "cannot show" list named proxying, the 5xx path and 413/415. All are now shown over real sockets. Its finding 1 is unchanged: the mock's `disconnect` ERROR line is on the mock's side, and the gateway answers 502. Its finding 4 holds: 4xx are visible only at DEBUG.

## What this run cannot show

- Real provider latency, errors and TLS: the mock is plain HTTP and imitates OpenAI's bodies. Real rate-limit headers, a real `insufficient_quota`, a real 404 and how a provider treats `"content":null` or `1E+2147483647` are unproven. No live call was made.
- The official SDKs: `openai` is not installed here, and none was installed for this run. `OpenAiSdkContractTest` is the only evidence; this run read the HTTP contract by hand.
- Connect, TLS and DNS timeouts: only a refused loopback port was exercised, so the 5 s and 10 s limits did not fire.
- Multi-replica effects: one gateway and one mock. Pool behaviour was seen at 2 connections, not 500.
- Load: 640 calls and short bursts are a correctness check. The latency numbers are single-client medians at 0 ms mock latency on a shared workstation, with other containers running. OPS-01 owns the p99 budget.
- Event-loop effects of a 10 MB body on other requests: only the single call's time was measured.
- Linux networking: Windows loopback only. How a dying peer closes (FIN or RST) varies by OS, so finding 1 may appear with a different split there.
- `llmgw.security.allow-unauthenticated-exposure=true`: not started, to avoid an all-interfaces bind on this machine.
- Spans, metrics, cost rows, Redis keys and broker events: GW-01 produces none.

## Revision (2026-11-03)

Everything above describes the first build. The GW-01 fix round followed (spec Log, "Review, smoke and fix round").

**Final jars.** `java -jar` of both boot jars, `HANG=1`, `LLMGW_PROVIDERS_OPENAI_REQUEST_TIMEOUT=2s`, DEBUG on our package, `GATEWAY_LOG` set: script 63 PASS, 0 FAIL, 2 SKIP, exit 0, both listeners on `127.0.0.1` only. The skips are scenarios 7 and 12, which stop or start a process. By hand on the same jars: with the mock stopped, 503 `provider_unavailable`, then 200 after its restart with no gateway restart; `LLMGW_BIND_ADDRESS=0.0.0.0` without the flag stopped the app naming `llmgw.security.allow-unauthenticated-exposure`. Gateway overhead median about 2.7 to 3.1 ms over the runs (informational, one client). The other hand checks of the Results table were not repeated on the final jars.

What became of each finding:

1. **Fixed.** A reset after the write is 502 `upstream_error`; 503 `provider_unavailable` is only for a call of which nothing was sent (spec section 4). `OpenAiTransportFailures` decides on the cause chain, and `RawProviderSocketIntegrationTest` "a reset after the request was written is UpstreamError, and is not retried" proves it on a raw socket. The `taskkill` case itself was not rerun.
2. **Decided, not changed.** An absent `content` stays `"content":null` upstream, listed in spec section 4 under "Narrower than OpenAI" and pinned by `ChatCompletionsEndToEndTest`. GW-06 meets it on assistant turns with `tool_calls`.
3. **Recorded.** The empty 400 stands, in the [systems entry](../systems/provider-adapters.md) Gotchas. The filter now matches the whole value ([bug](../bugs/loopback-host-filter-passes-malformed-host-values.md)).
4. **Recorded.** No change to the log lines; the systems entry's Gotchas name OBS-03 and COST-02.
5. **Fixed.** `ChatHandler` writes bytes, so the rule no longer depends on that logger ([bug](../bugs/completion-text-in-spring-encoder-debug-log.md), [gotcha](../gotchas/spring-jackson-encoder-logs-the-value-at-debug.md)). `NoLeakIntegrationTest` runs with `logging.level.web=TRACE`.
6. **Documented.** Spec section 4, "Narrower than OpenAI"; pinned by `ChatCompletionsEndToEndTest`.
7. **Unchanged.** OPS-01 owns it (spec section 9).
8. **One point fixed.** `mock-provider` binds `127.0.0.1` by default, and its image sets `SERVER_ADDRESS=0.0.0.0`. The script still omits 403, 408, 413 and 422 and checks status and `code` only; `UpstreamErrorMappingTest` asserts every row's exact envelope. The other points stand.

Still unproven, as "What this run cannot show" says: real provider errors and TLS. No live call was made.
