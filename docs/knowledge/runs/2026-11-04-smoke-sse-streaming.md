---
id: runs/2026-11-04-smoke-sse-streaming
type: run
kind: smoke
title: SSE streaming end to end against mock-provider - GW-02 section 8 scenarios, framing, commit point, deadlines, disconnects, slow clients, log canaries
status: active
build: 3426a17 + uncommitted GW-02 (first run), 3426a17 + uncommitted GW-02 fix round (Revision)
area_tested: "GW-02 stream: true on POST /v1/chat/completions, OpenAI stream adapter, SseBodyInserter, ChatStreamFrames, client-write-timeout, tools/smoke/streaming.sh, tools/smoke/chat-completions.sh"
verdict: pass-with-findings
tags: [smoke, gw-02, streaming, sse, cancellation, back-pressure, reactor-netty, logging]
related: [bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading, bugs/adapter-broken-chunked-framing-raised-as-a-bug, systems/provider-adapters, systems/mock-provider, systems/request-context-and-errors, runs/2026-11-03-smoke-chat-completions, patterns/cold-event-flow-commits-at-first-frame, patterns/raw-sse-frames-flushed-per-event, patterns/copy-and-release-pooled-buffers-before-a-flow, gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/streaming.sh
  - tools/smoke/chat-completions.sh
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatRequestReader.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamLog.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ServerStreamConfiguration.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/SseBodyInserter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
---

## Verdict

First run below; the rerun on the fix build is `pass-with-findings`, see [Revision](#revision-2026-11-04).

`fail`, on two findings, neither in the section 8 script. All 13 section 8 scenarios pass at default settings, both scripts exit 0 with 0 FAIL, and every commit-point, deadline, disconnect and canary check passes.

- Finding 1: a request section 4 declares valid (`"include_usage": null`) gets a 500.
- Finding 2: `client-write-timeout` does not measure what section 5 says, and a client that keeps reading loses the stream, against "A slow client slows the provider; nothing is dropped."

## What was run

- **Stack:** no compose stack. `docker compose -f deploy/compose.yaml ps` was empty before and after, and GW-02 calls no Redis, Postgres or broker, emits no span and has no admin API (spec section 5, Data and Observability), so `stack-check.sh`, the Redis, Jaeger, Postgres and broker checks, seeding and the auth baseline do not apply. Windows 11, Spring Boot 4.1.2, Java 25.0.4.1 under `bootRun`, Reactor Netty 1.3.8. Ports 8080 and 8090 were free before the run.
- **mock-provider:** `./gradlew :mock-provider:bootRun`, PID 12764; killed on purpose in block E, then PID 39656, then PID 44404 for start 8. Default 0 ms latency, `POST /__mock/reset` before each block, behaviour from `mock-chat` scenarios.
- **No real provider:** every start used the `local` profile, no `OPENAI_*` or `LLMGW_*` variable was in the shell beforehand, and `api.openai.com` appears 0 times in all gateway logs.
- **gateway-app, nine starts and four refused starts, profile `local`:**

| Start | PID | Settings | Used for |
|---|---|---|---|
| 1 | 22992 | DEBUG on our package, default timeouts | both scripts, blocks A to E, scenario 12 at 30 s |
| 2 | 18524 | DEBUG, idle 2 s, first byte 2 s, client write 1 s | `HANG=1 streaming.sh`, block F, scenario 12 at 1 s |
| 3 | 44308 | DEBUG, total 1 s, idle 1 s, first byte 1 s, client write 500 ms | block G |
| 4 | 45956 | DEBUG, `max-event-size` 1KB, `max-response-size` 2KB | block H |
| 5 | 24452 | DEBUG on our package, `logging.level.web=TRACE` | canaries |
| 5b | 42168 | root DEBUG, `logging.level.web=TRACE` | canaries, beyond the spec |
| 6 | 24112 | defaults, INFO | time to first chunk, scenario 12 steady readers |
| 7 | 35380 | DEBUG, `REQUEST_TIMEOUT=2s`, pool 2, life and idle 1 s, acquire 1 s | `HANG=1 chat-completions.sh`, block M |
| 8 | 2740 | INFO, idle 2 s, client write 1 s | 6 memory rounds |

- **Tools:** curl and a stdlib Python raw-socket client that keeps each chunk's bytes and arrival time from request start. Raw outputs are in the session scratchpad, not the repo.
- **Beside this run:** four containers of another project ran throughout and were not touched. Treat the latency numbers as noisy.

## Results

Check counts:

| Script | Start | PASS | SKIP | FAIL | Exit |
|---|---|---|---|---|---|
| `streaming.sh` | 1 | 62 | 1 (8, needs `HANG=1`) | 0 | 0 |
| `HANG=1 streaming.sh` | 2 | 66 | 0 | 0 | 0 |
| `chat-completions.sh` | 1 | 59 | 3 (6, 7, 12) | 0 | 0 |
| `HANG=1 chat-completions.sh` | 7 | 61 | 2 (7, 12, manual) | 0 | 0 |

By hand 184 checks recorded after harness corrections: 180 passed, 2 failed (findings 1 and 2), 2 void (finding 8, replaced by the redone D2).

| # | Scenario | Expected | Observed | Result |
|---|---|---|---|---|
| 1 | Headers | section 4 set, UUIDv7 | `HTTP/1.1 200 OK`, exactly `transfer-encoding: chunked`, `x-request-id`, `x-client-request-id`, `Content-Type: text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, `x-accel-buffering: no`, `x-gateway-deployment: openai`; no `content-length`, `connection` or `x-gateway-usage-estimated` | pass |
| 2 | Frames | `data: <compact JSON>\n\n`, then `data: [DONE]\n\n` | raw socket: 9 frames, each compact and re-serialisable byte for byte, one HTTP chunk per frame, `0\r\n\r\n`, nothing after the last frame; `Olá, coração` and an emoji intact | pass |
| 2 | Against the mock's direct stream | equal after `id`, `created` | 9 events, each equal to the direct event | pass |
| 3 | Correlation | one entry, `include_usage` true upstream | one journal entry per ID, upstream `stream: true`, `accept: text/event-stream` | pass |
| 4 | Usage asked | `usage` null except the last, `choices: []` | as expected; no `x_gateway` | pass |
| 4 | Usage not asked or false | no `usage`, no empty `choices`, upstream still asked | as expected; journal `stream_options` `{"include_usage":true}`; `include_obfuscation` reaches the mock unchanged | pass |
| 4 | `include_usage: null` | section 4: boolean or null | 500 `internal_error` | **fail, finding 1** |
| 4 | Estimated usage | last chunk flagged, only when asked | `{...,"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":6,"total_tokens":13},"x_gateway":{"usage_estimated":true}}`, then `[DONE]`; not asked: no usage chunk; no header | pass |
| 5 | Spread, `ttftMs` 300, `perTokenMs` 200 | first byte near ttft, chunks spread | gateway arrivals 309, 309, 511, 711, 912, 1114, 1315, 1517, 1718, 1920, 1921, 1921 ms; direct 306, 306, 507, ..., 1917; first byte median of 10: 308.7 ms; first to last content 1409 ms; gateway minus direct per frame 0.7 to 3.7 ms | pass |
| 6 | Pre-commit rows | status, envelope, no stream headers | upstream 429 gives 429 `upstream_rate_limited` with `retry-after: 7`, `retry-after-ms: 7000`; quota gives 503 `upstream_quota_exhausted`, `x-should-retry`; 500 gives 502 `upstream_error`; 503 gives 503 `provider_unavailable`, `retry-after`; 504 gives 504; 401 gives 502 `upstream_auth_failed`; 400 relays `code` and `param`; 404 gives `model_not_found`. All `application/json`, `content-length`, no `cache-control`, `x-accel-buffering`, `x-gateway-deployment`, no provider message | pass |
| 6 | Closed port (mock killed) | 503 `provider_unavailable` | 5 of 5 exact, first byte 1.1 to 19.8 ms, no `retry-after` | pass |
| 6 | Recovery | 200, no gateway restart | PID 22992 unchanged, 200 with `[DONE]`, journal seq 1 on the new mock | pass |
| 7 | `disconnect`, `malformed`, `stream_error` | one error frame, no `[DONE]`, clean end | each: 200, 3 content frames, one `data: {"error":...}` with exactly `message, type, param, code`, `0\r\n\r\n`, connection stays open | pass |
| 7 | In-band table rows | section 4 table | `rate_limit_error` gives `upstream_rate_limited`; quota gives `upstream_quota_exhausted`; `model_not_found` with `param: model`; `invalid_request_error` relays `bad_thing`, `top_p`; each with the row's exact message | pass |
| 7 | `afterPieces: 0` | commit at the opening | 200, opening frame, error frame | pass |
| 7 | Mock killed mid-stream, beyond the spec | error frame | 137 ms after `taskkill`: `upstream_error` frame, clean end; adapter WARN `SocketException` | pass |
| 7 | Log per failure | one ERROR with the ID, no second line | 1 ERROR `Stream failed` and 1 adapter WARN per request, on all 10 post-commit requests; 0 `Error finishing response`; 0 mapper lines after commit; 0 lines without an ID | pass |
| 8 | Stall, idle 2 s | `upstream_timeout` frame | frame 2001 ms after the last piece, clean end, `client_cancelled` in 23 ms | pass |
| 8 | Hang, first byte 2 s | 504 envelope | 504 at 2008 ms, `client_cancelled`, mock `durationMs` 1999 | pass |
| 8 | Margins | below passes, above fails | `ttftMs` 1500: 200; 2500: 504 at 2007 ms; gap 1500 ms: 7512 ms stream completes; gap 2500 ms: frame at 2007 ms | pass |
| 8 | Total 1 s, never idle | `upstream_timeout` frame | frame at 1045 ms, largest gap 204 ms, `client_cancelled` in 2 ms, connection reusable; `ttftMs` 1500 gives 504 at 1011 ms | pass |
| 8 | Event over `max-event-size` | `upstream_error`, before or after commit | after commit: frame, the oversized text not sent; before commit (1200-byte model name): 502 envelope; upstream line of 1024 bytes passes, 1025 fails | pass |
| 8 | Stream longer than `max-response-size` | completes | 14706 bytes with a 2 KB limit: `[DONE]`; error body over the limit: 502, no text | pass |
| 8 | Startup refusals | exit non-zero, names the property | first byte 31 m, idle 31 m, write 60 s, event 512 B: 4 of 4 exit 1, no bind, message names the property | pass |
| 9 | FIN after the first frame | `client_cancelled` within 2 s | 5 of 5, 2 to 17 ms after the close, `piecesSent` 1 of 200, unread bytes checked empty | pass |
| 9 | RST after the first frame | same | 6 of 6 (the first version's three "FIN" rows were RSTs, finding 8), 2 to 19 ms, `piecesSent` 2 of 200 | pass |
| 9 | Before the first frame | same | FIN 4 ms, RST 2 ms, `outcome` `client_cancelled`, `Stream cancelled` with `chunks=0 ttftMs=null` | pass |
| 9 | 30 concurrent disconnects | all cancelled, `inFlight` 0 | 30 of 30, worst 25 ms, `inFlight` 0, 0 upstream connections left, next stream 200; 30 `Stream cancelled` lines, 0 ERROR | pass |
| 9 | Closing at the last frames, 50 rounds | one end line each | 27 cancelled, 23 completed, every ID exactly one line, 0 ERROR; mock said 26 and 24 | pass |
| 9 | Log | one INFO with the ID, no ERROR | every cancelled ID: 1 `Stream cancelled`, 0 ERROR, 0 WARN with the ID. GW-01 finding 4 is closed for streams | pass |
| 10 | Canaries in responses | none | 12 of 12 response paths clean, at each of starts 5 and 5b | pass |
| 10 | Canaries in logs | none | 17 strings (prompt, completion, provider message, client key, `mock-local-key`, cookie, `Bearer`, `"messages"`, `"content"`, invalid value, option and metadata values, upstream header): 0 hits in 274 lines (85 TRACE, 61 DEBUG, 7 ERROR) at `web=TRACE`, 0 in 2088 lines (84 TRACE, 996 DEBUG) at root DEBUG; `authorization` 0; 0 in the `HANG=1` DEBUG log | pass |
| 11 | Rejections | exact envelopes, no upstream call | 7 cases exact, including `stream_options` without `stream` and `stream: false`; `lastSeq` unchanged; `stream_options: null` without `stream` gives 200 | pass |
| 12 | Reads slowly then resumes, 30 s | everything in order | 5 s pause, 2.5 MB in slow steps, 5 s pause: 500 of 500 tokens in order, 8116986 bytes, `[DONE]`, `0\r\n\r\n`, 11.2 s | pass |
| 12 | Same at 1 s | same | 0.5 s pauses: cut at 1797 ms, 174 of 500 tokens | **fail, finding 2** |
| 12 | Steady reader at 40 KB/s, 30 s | slowed, nothing dropped | cut at 30171 ms, 1.4 of 8 MB, longest gap between reads 216 ms | **fail, finding 2** |
| 12 | Steady reader at 160 KB/s, 30 s | slowed, nothing dropped | not cut; mock completed; the client stopped itself at 45 s with 6.8 MB read | pass |
| 12 | Stops reading | cut by `client-write-timeout` | 30.01 s at 30 s; 1.01 s at 1 s; client ends at EOF with no zero chunk; `Stream cancelled`; `client_cancelled`; 0 upstream connections after | pass |
| 12 | Back-pressure | plateau, under 4096 frames of 16 KB | `chunks=152` on all 21 clients stalled at 30 s, mock stopped at 270 of 500 pieces; client had 162890 bytes | pass, finding 5 |
| 12 | Memory | stable | below | pass, finding 5 |
| 12 | Stalled, stream fits the queues | `cancelled` line | `Stream completed` at DEBUG, client got 97926 of about 960000 bytes | finding 3 |
| 13 | Time to first chunk | informational | below | info |
| - | Second request on the connection | works | stream, non-stream, stream, non-stream on one socket; also after an error frame, after a pre-commit 502 and after a total-timeout frame | pass |
| - | Concurrency, beyond the spec | no cross-talk | 320 streams at 32 in 0.37 s, echo mode: each its own text, 320 unique IDs | pass |
| - | Pool of 2 | recovers | 20 cancelled in a row all 200, next stream 200, `inFlight` 0; 2 held: third 503 `provider_unavailable` at 1030 ms before commit | pass |
| - | `max-life-time` 1 s | stream not cut | 2419 ms stream completes | pass |
| - | `Accept: application/json`, `text/plain`, `Accept-Encoding: gzip` | still SSE, not compressed | as expected | pass |

Memory, 20 clients stalled on 8 MB streams, MB, default 30 s (start 1, DEBUG):

| Moment | Heap used | Working set | Private | Threads |
|---|---|---|---|---|
| Before, after GC | 21 | 246 | 299 | 98 |
| Stalled, no GC | 59 | 444 | 560 | 100 |
| Stalled, after GC | 33 | 326 | 437 | 100 |
| After the cut, after GC | 21 | 269 | 380 | 98 |

One client stalled: heap 21 to 21, working set 243 to 246. If the stream were buffered whole the heap would grow by up to 160 MB.

Six rounds of 20 stalled clients at 1 s (start 8, INFO, 120 clients, 120 `Stream cancelled`, 0 ERROR), after GC:

| After round | 1 | 2 | 3 | 4 | 5 | 6 |
|---|---|---|---|---|---|---|
| Heap used | 24 | 24 | 24 | 24 | 24 | 24 |
| Working set | 274 | 278 | 286 | 287 | 287 | 291 |
| Private | 325 | 329 | 337 | 338 | 338 | 354 |

The heap is flat. Working set rose 17 MB and private 29 MB from round 1 to 6; this run does not attribute that native growth.

Time to first chunk, mock at 0 ms, start 6 at INFO, after 200 warm-up calls each, three rounds of 50:

| Client | Direct p50 | Gateway p50 | Added p50, median of rounds | Gateway p95 per round |
|---|---|---|---|---|
| Python, keep-alive | 0.27 to 0.30 ms | 0.98 to 1.20 ms | 0.82 ms | 1.35 to 4.24 ms |
| Python, new connection | 0.41 to 0.43 ms | 1.42 to 3.04 ms | 1.12 ms | 1.83 to 4.76 ms |
| curl, new process | 1.64 to 1.66 ms | 2.79 to 2.81 ms | 1.15 ms | 3.08 to 3.32 ms |
| curl, script scenario 13, DEBUG | 2.69 and 2.88 ms | 6.22 and 6.56 ms | 3.53 and 3.68 ms | not measured |

Against the [last run in this area](2026-11-03-smoke-chat-completions.md): cancellation reaches the mock in 2 to 25 ms, GW-01 measured 16 to 19 ms. A closed port answers in 1.1 to 19.8 ms, GW-01 1.7 to 24.8 ms. GW-01's 2.7 ms measured a whole response at DEBUG, so it does not compare with these first-chunk numbers. Its finding 4 is closed for streams, and its finding 1 did not recur: a mock killed mid-stream gives `upstream_error`.

## Findings

1. **`"include_usage": null` with `stream: true` gives a 500** (bug, breaks spec section 4).
   - Request: `POST /v1/chat/completions {"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true,"stream_options":{"include_usage":null}}`.
   - Response: `500`, `x-request-id: 01a1a48a-8daf-7268-a861-2b98bf78b293`, `{"error":{...,"type":"server_error","param":null,"code":"internal_error"}}`. No upstream call: the journal's last entry was the earlier request.
   - Log: `ERROR ... Request failed with status 500 (internal_error)`, `tools.jackson.databind.exc.JsonNodeException: 'NullNode' method booleanValue() cannot convert value <null> to boolean`, at `ChatRequestReader.streamOptions(ChatRequestReader.kt:72)`.
   - Cause: line 68 lets `null` through, and line 72 calls `includeUsage?.booleanValue()`: the `?.` guards an absent member, not a `NullNode`, and Jackson 3 throws on it.
   - Scope: 500 also with another member beside it. Without `stream: true` it is the correct 400, because that check runs first. `false` gives 200. Any client can write an ERROR line and stack trace at will.
   - Not caught by: `ChatRequestReaderTest` and `ChatStreamRouteTest` have no `include_usage: null` case, and neither does `streaming.sh`.

2. **`client-write-timeout` bounds how long the backlog takes to drain, not one write, so a client that keeps reading is cut** (behaviour contradicts the spec).
   - Section 5 says "One write to the client's socket" and "A slow client slows the provider; nothing is dropped"; the systems entry repeats the first.
   - Measured with 16 KB frames, 500 pieces, the mock at 0 ms latency. Every client below read continuously:

   | Timeout | Client rate | Longest gap between reads | Gateway `durationMs` | Bytes received of 8 MB | Request ID |
   |---|---|---|---|---|---|
   | 1 s | 668 KB/s | 48 ms | 1177, cancelled | 0.9 MB | `01a1a491-f641-7c07-9a68-bd772d6c58d4` |
   | 1 s | 216 KB/s | 79 ms | 1133, cancelled | 0.4 MB | `01a1a491-fc55-7bd4-8a34-598edd5c24c7` |
   | 1 s | 66 KB/s | 141 ms | 1130, cancelled | 0.2 MB | `01a1a492-042e-7e81-9c11-87b0175ea652` |
   | 30 s | 39 KB/s | 216 ms | 30171, cancelled | 1.4 MB | `01a1a495-ca69-77a8-a5ba-b7d43ab6f819` |
   | 30 s | 147 KB/s | 115 ms | not cut, journal `completed` | 6.8 MB read when the client stopped at 45 s | `01a1a496-53e6-72af-a75b-1970e49853e6` |

   - The cut comes at about the timeout whatever the rate, so the timer runs against the queue, not against progress. Inference from those numbers, and from `WriteTimeoutHandler` timing each write from the moment it is queued: a frame waits behind the backlog, and the write fails if the backlog takes longer than the timeout to drain.
   - The same at 1 s: a client that paused 0.5 s, half the timeout, then read on was cut at 1797 ms with 174 of 500 tokens (`01a1a491-afed-764d-a52e-1209a978247d`, `chunks=272`).
   - The client gets the frames so far, then EOF with no zero chunk, no error frame and no `[DONE]`. The log says `Stream cancelled`, as for a stalled client.
   - Rough threshold, estimated and not measured: backlog over timeout. At the default 30 s that is about 70 KB/s for 16 KB frames. For ordinary frames of about 270 B, the backlog of about 150 frames is about 40 KB, so about 1.3 KB/s. Default settings with ordinary chunks are therefore rarely affected. Frames near `max-event-size`, or any lowered timeout, are. The script header's own `HANG=1` setting of 1 s is one.
   - The accepted corner in section 5 ("each write still finishes inside `client-write-timeout`") assumes the timer is per socket write. Gotcha candidate. The decision is the spec's: change the mechanism, or change the contract and its wording.

3. **A stalled client whose stream fits the queues is reported as completed** (low; AC wording). Client read 4096 bytes and stopped; 60 frames of 16 KB.
   - Gateway: `Stream completed`, `chunks=62`, `durationMs` 42 at 30 s (`01a1a48e-ab65-7555-b11e-a45cc1382608`) and 51 at 1 s (`01a1a492-110b-76d7-8e51-b2798fcaaa48`). Journal `completed`.
   - At the write timeout the connection closed. The client had 97926 of about 960000 bytes, no `[DONE]`.
   - AC "the end-of-stream line says `cancelled`" holds only when the stream exceeds what the queues hold: with 800 ordinary frames the line was `Stream cancelled`, `chunks=464` (`01a1a497-0456-7a04-87e5-a9a0b86020c7`). "Terminal beats cancelled" is right for usage: the provider did finish. But `Stream completed` is DEBUG, so at INFO the only trace of this client losing the answer is finding 4's WARN, which has no request ID.

4. **`FluxReceive`'s WARN fires on every client RST and on every write timeout, with no request ID** (info, gotcha candidate; answers the spec's open question).
   - RST: `WARN reactor.netty.channel.FluxReceive : [<conn>, L:/127.0.0.1:8080 - R:/127.0.0.1:<port>] An exception has been observed post termination, use DEBUG level to see the full stack: java.net.SocketException: Connection reset`. True FIN: 0 lines in 5 closes, 0 in 3 half-closes, 0 for a FIN during `hang`, against 1 for the RST during `hang`.
   - Write timeout: the same line with `io.netty.handler.timeout.WriteTimeoutException`, 22 in the 30 s block and 120 in start 8, one per cut connection, no other WARN or ERROR.
   - The line carries the peer port and connection ID, not the request ID, and `Stream cancelled` gives the ID, so the two can be matched only by time.
   - A socket closed with unread bytes sends RST, so on a busy stream most real disconnects are RSTs: 54 lines in block D.

5. **Back-pressure figures** (info). The flow stopped at `chunks=152` for all 21 clients stalled on 16 KB frames at 30 s, about 2.4 MB per stream, and at 464 on ordinary frames. The mock stopped at 270 of 500 pieces, so 118 pieces sat in transport buffers. Section 5 reads "about 100 frames plus 8 upstream reads" from source; observed is about 1.5 times that, and well under the 4096 limit of the AC. Worst case at the default `max-event-size` of 1 MB would be about 150 MB per stream, if the same frame count holds: not tested.

6. **An HTTP/1.0 client gets `HTTP/1.1 200 OK`, `transfer-encoding: chunked`, `connection: close`** (info). A real HTTP/1.0 client cannot decode chunked. Not in the spec, and Reactor Netty's behaviour rather than ours.

7. **A half-close cancels the stream** (info). A client that sends `shutdown(SHUT_WR)` after its request and keeps reading is treated as gone: 3 of 3 `client_cancelled`, 2 to 17 ms, socket closed. Reactor Netty's default. Rare for HTTP clients, but a tool that half-closes gets no stream.

8. **Harness errors in this run, corrected** (info).
   - Python's `close()` with unread bytes sends RST, so the first "FIN" rows of D1 were RSTs. Redone with an empty receive buffer, and with `perTokenMs` 300 so nothing arrives before the close.
   - `GET /__mock/requests?limit=1` returned an old row, not the latest, so the first D2 passed on the wrong entry. Redone with a reset per case. Those 2 checks are the void ones.
   - The first block C assumed a wrong 503 code and a request ID in every message. Corrected to GW-01's rows, and now compares the exact message.
   - The first H3 measured a direct stream without `include_usage`, 13 bytes shorter than what the gateway parses. Re-measured: the limit is exact.
   - The synthesised usage chunk has no `system_fingerprint`, which provider chunks have. The spec names only `id`, `created`, `model` for it, so this is an observation, not a failure.

## What this run cannot show

- Real provider latency, errors and framing: real keep-alives, whether a provider closes promptly after `[DONE]`, real in-band error shapes, TLS. The in-band table was tested only with shapes the mock produces. No live call was made.
- What the mock cannot send: CRLF, comments, split UTF-8 across upstream reads, an in-band error first, EOF without `[DONE]`, a 200 with an empty or non-SSE body. Those rest on the pure and raw-socket tests. No other upstream was stood up, to keep every route on `mock-provider`.
- The official SDKs: `OpenAiSdkStreamContractTest` is the evidence; this run read the wire by hand.
- `ByteBuf` leaks: no `PARANOID` leak detector ran here. The memory table shows a flat heap, not the absence of a direct-buffer leak, and the native growth in the 6 rounds is unattributed. Six rounds are too few to separate a plateau from slow growth.
- The ordinary-frame threshold of finding 2 is an estimate: only 16 KB frames were measured against a steady reader. Worst-case 1 MB frames were not exercised.
- Multi-replica effects, and proxies: one gateway, one mock, no nginx, so `x-accel-buffering: no` was seen but its effect was not.
- Load: 320 streams at concurrency 32 and 30 concurrent disconnects are correctness checks. Latency numbers are single-client medians on a shared workstation with other containers running. OPS-01 owns the p99 budget.
- Linux networking: Windows loopback only. Socket buffer sizes set where back-pressure begins, so `chunks=152` and the bytes a stalled client holds will differ, and how a peer closes (FIN or RST) varies by OS.
- Graceful shutdown during a stream, and a bug raised in the flow after commit: neither was triggered.
- Spans, metrics, Redis keys, ledger rows and broker events: GW-02 produces none.

## Revision (2026-11-04)

Everything above describes the first build. This is the rerun on the fix build, still uncommitted on 3426a17. New verdict: `pass-with-findings`. Findings 1 and 2 are fixed, and the stated stall limit matches the measurements. The new finding R1 is that the stall limit is not the only limit on a slow reader. It sits inside a corner section 5 already accepts, so it does not fail the run.

**How it ran.** `mock-provider` by `bootRun`, gateway by `bootRun`, profile `local`, ports 8080 and 8090 free before and after. No real provider; `api.openai.com` appears 0 times in the logs. For item 4 only, a stdlib Python raw socket on `127.0.0.1:8092` stood in as the provider, through `LLMGW_PROVIDERS_OPENAI_BASE_URL`; it counted every request it received, and the mock's `lastSeq` stayed unchanged.

| Start | PID | Settings | Used for |
|---|---|---|---|
| R1 | 38612 | DEBUG, idle 2 s, first byte 2 s, client write 1 s | `HANG=1 streaming.sh`, finding 1, blocks F and S12, rates at the original settings |
| R2 | 18024 | INFO, client write 1 s, idle 60 s | rates |
| R3 | 45376 | defaults, INFO | rates, blocks A to D, `chat-completions.sh` |
| R4 | 40420 | DEBUG, base URL on the raw socket | broken chunks |
| R5 | 41288 | DEBUG, `logging.level.web=TRACE` | canaries |
| R6 | 17440 | DEBUG, total 1 s, `max-event-size` 1KB, `max-response-size` 2KB | blocks G and H |
| R7 | 17716 | INFO, client write 1 s, Native Memory Tracking | 30 memory rounds |
| R8 | 22724 | defaults, DEBUG | stopped client at 30 s |

**Rerun results.**

| Script | Start | PASS | SKIP | FAIL | Exit |
|---|---|---|---|---|---|
| `HANG=1 streaming.sh` | R1 | 68 | 0 | 0 | 0 |
| `chat-completions.sh` | R3 | 59 | 3 (6, 7, 12) | 0 | 0 |

By hand 185 checks, 185 passed, after the harness corrections in R5; the rate tables below are measurements, not counted as checks.

| Item | Expected | Observed | Result |
|---|---|---|---|
| Finding 1: `include_usage: null`, alone | 200, no usage chunk | 200, 5 frames, `[DONE]`, no `usage`, upstream `{"include_usage":true}` (`01a1a4ba-6e5d-7ea1-b7cb-2583fcdf356c`) | fixed |
| Finding 1: with a sibling, either order | same | 200, no `usage`, `include_obfuscation` reaches the mock unchanged (`01a1a4ba-6e65-718e-8d73-1d37f640224f`, `01a1a4ba-6e6e-7231-aa3e-7521cf837f73`) | fixed |
| Finding 1: neighbours | unchanged | null without `stream` 400; string 400; `true` has the chunk; `false` has none | pass |
| Finding 2: paused 0.5 s under a 1 s timeout | every frame | 500 of 500 tokens in order, 8116986 bytes, `[DONE]`, `0\r\n\r\n` (`01a1a4ba-e8dd-7a0e-88a0-d20ba0c1f9fc`) | fixed |
| Finding 2: slow readers | not cut, above the stated limit | tables below | fixed, see R1 |
| Stopped client, 1 s | cut 1 to 1.5 timeouts after the last progress, socket EOF | 1.26 s, 5 of 5; EOF, no zero chunk, `client_cancelled`, `piecesSent` 270 of 500 | pass |
| Stopped client, 30 s | same | 37.51 s and 45.03 s; EOF, no zero chunk. The upper bound was reached, within the journal poll | pass |
| Second stream on a kept-alive connection stalls | cut | 1.26 s at 1 s, 37.51 s at 30 s | pass |
| Non-stream body, client stops | cut | 262300 of 8000000 bytes, EOF, at 1 s and at 30 s; `Closing connection ...: 8001059 bytes pending` | pass |
| Nothing pending | left alone | small stream read 3 s and 90 s late: complete; connection idle 3 s and 90 s: next request 200; `ttftMs` 1500 and token gaps of 1500 ms under a 1 s timeout complete | pass |
| Finding 3 | as the systems entry | at 1 s: `Stream completed`, `chunks=62`, `durationMs` 58, journal `completed`, client got 97926 of about 960000 bytes, no `[DONE]` (`01a1a4bc-5dda-72ff-a6e5-a1f9e5e240f6`). At 30 s the client resumed at 32 s, before the 37.5 to 45 s window, and got all 975192 bytes | as documented |
| Finding 4, write timeout | old WARN gone, one INFO | 0 `WriteTimeoutException` across all rerun logs; 710 lines `Closing connection <id>: no write progress, <n> bytes pending`, one per cut, INFO, no request ID in MDC | pass |
| Finding 4, RST | WARN stays, as documented | 55 `FluxReceive` lines in block D, all `Connection reset`; 0 on 5 true FINs and 3 half-closes | as documented |
| Broken chunk: `ZZZ` first | 502 | 502 `upstream_error`, adapter WARN `NumberFormatException`, mapper ERROR (`01a1a4c9-562c-775c-863a-1dc5b945fed6`) | pass |
| Broken chunk: `ZZZ` mid-stream | `upstream_error` frame | 200, frame, clean end, 1 ERROR `Stream failed`, no `internal_error` | pass |
| Broken chunk: `ZZZ` in the drain | silent | `[DONE]`, `Stream completed`, no frame after it | pass |
| 20000-byte line, first and mid-stream | 502; frame | as expected | pass |
| Raw cases the mock cannot send | section 5 tables | empty 200: 502; not SSE first: 502; not SSE mid-stream: frame; in-band error first: 502; EOF with `finish_reason`: `[DONE]`; EOF without it: frame; CRLF and comments: `[DONE]`; `café` split across upstream chunks: intact | pass |
| Raw block log | no bug path | 14 of 14 requests at the raw provider, 0 `internal_error`, 0 `crashed`, 0 `status=null`, 0 canaries, 0 `Error finishing response` | pass |
| Regression by hand | as the first run | blocks A 52/52, B 5/5, C 15/15, D 21/21, D2 3/3, D3 5/5, F 10/10, G 7/7, H 4/4 plus the boundary 2/2. Cancel 2 to 32 ms; 50 race rounds no ERROR. 20 stalled clients, 3 rounds: 20 of 20 each | pass |
| Canaries, `web=TRACE` | none | 13/13 on responses; 0 hits for 17 strings in 216 lines (86 TRACE, 63 DEBUG, 6 ERROR) | pass |

Slow readers against the first run's table, 16 KB frames, 500 pieces, 8 MB, each row 3 clients unless noted, reading continuously. Tokens are always in order. Rates are real rates, and the 8 KB column is the client's `SO_RCVBUF`.

| Timeout, idle | Rate, KB/s | First run | Rerun | Clients' longest gap |
|---|---|---|---|---|
| 1 s, 2 s (first run's settings) | 683 | cut, 0.9 MB | complete, 500/500, 11.6 s | 101 ms |
| | 205 | cut, 0.4 MB | `upstream_timeout` frame, 151 to 152 tokens, clean end | 132 ms |
| | 146, 114 | not run | `upstream_timeout` frame, 151 to 153 tokens | 162 ms |
| | 71, 55, 29 | 66 KB/s cut | stall cut, EOF, 11 to 22 tokens | 267 ms |
| 1 s, 60 s, 8 KB | 686, 206, 146, 114, 85.6 | | complete, 500/500, 11.6 to 92.7 s | 179 ms |
| | 70.8, 55.4, 28.8 | | stall cut, EOF, 11 to 22 tokens | 268 ms |
| 1 s, 60 s, OS buffer | 684, 206, 147 | | complete, 500/500 | 172 ms |
| | 71.7, 56.3, 29.4, 15.1 | | stall cut, EOF, 19 to 30 tokens | 528 ms |

Default 30 s and 60 s, 2 clients per row, 470 s limit on the client. "Server end" is `durationMs` of the end line.

| Rate, KB/s | First run | Rerun | Server end |
|---|---|---|---|
| 147 | not cut | complete, 500/500, 54 s | completed |
| 39 (also OS buffer) | cut at 30171 ms, 1.4 MB | complete, 500/500, 203 s | completed |
| 19.7 | not run | complete, 500/500, 402 s | completed |
| 11.8 | not run | `upstream_timeout` frame at 152 tokens, client sees it at 204 s | failed at 71 s (OS buffer 61 s) |
| 7.9 | not run | same, client sees it at 305 s | failed at 106 s |
| 6.0 | not run | same, client sees it at 405 s | failed at 141 s |
| 4.0 | not run | still reading at 472 s, 118 tokens, frame queued | failed at 211 s |
| 3.0 | not run | still reading at 471 s, 88 tokens, frame queued | failed at 281 s |
| 2.9, OS buffer | not run | stall cut, EOF, 35 tokens | cancelled at 108 s |
| 1.9 | not run | stall cut, EOF, 16 tokens | cancelled at 78 s |
| 0.9 | not run | stall cut, EOF, 11 tokens | cancelled at 54 s |

Ordinary frames, about 250 bytes, the provider at 50 tokens/s, 1500 tokens, 375519 bytes, defaults, one client each: 7.3 KB/s and 2.9 KB/s complete; 984 B/s stall cut at 76.8 s; 297 B/s stall cut at 60.0 s. The mock finished all 1500 pieces in each.

**Against the stated limit.**

- 1 s, idle 60 s: stated 160 KB/s held, 80 and 32 KB/s cut. Measured the same, and tighter: holds at 85.6, cut at 70.8. That puts the kernel step at about 75 to 85 KB.
- 30 s: stated about 3 KB/s, estimated. Measured: 3.0 KB/s not cut by the handler, 1.9 cut (8 KB buffer); 2.9 cut (OS buffer, 16 KB frames); 2.9 held and 0.98 cut (ordinary frames). The estimate is right: the edge is 2 to 3 KB/s, and which side of it a client lands on varies with the buffer.
- It matches as a limit on the connection, not as a limit on the stream: see R1.

**Memory.** 30 rounds of 20 stalled clients, 8 MB streams, write 1 s, after GC, MB. 600 `Closing connection`, 600 `Stream cancelled`, 0 ERROR.

| After round | Start | 5 | 10 | 15 | 20 | 25 | 30 |
|---|---|---|---|---|---|---|---|
| Heap used | 23 | 24 | 25 | 21 | 21 | 21 | 21 |
| Working set | 212 | 291 | 294 | 278 | 296 | 297 | 297 |
| NMT committed | 244 | 321 | 328 | 308 | 328 | 327 | 327 |
| NMT `Other` | 0 | 51 | 52 | 52 | 52 | 53 | 53 |
| NMT Java heap committed | 80 | 96 | 96 | 80 | 96 | 96 | 96 |

This answers the first run's unattributed drift. The rise comes in the first rounds and then holds: `Other` (direct buffers, by NMT's category) gives 51 MB and committed heap 16 MB, and the working set is 296 to 297 MB from round 20 to 30. A plateau, not a slope.

**What became of each finding.**

1. Fixed. `ChatRequestReader` reads a boolean only when the node is one.
2. Fixed as `ClientStallHandler`, with the limits above and R1.
3. Unchanged, now documented in the systems entry; reproduced at 1 s.
4. Half done, as documented: the write-timeout WARN became the INFO; the RST WARN stays.
5. Unchanged: `chunks=152` again, 1.93 MB pending per stalled stream at the cut.
6, 7. Unchanged.
8. Replaced by R5.

**New findings.**

- **R1. Above the stall limit a slow reader is still not safe: below about 17 KB/s on 16 KB frames it gets an `upstream_timeout` frame** (medium, documentation; inside an accepted corner).
  - `client-write-timeout` no longer cuts these clients, so the upstream `idle-timeout` is now what ends them. Between 3.0 and 11.8 KB/s at the defaults the client is alive and reading, and the stream ends after about 152 of 500 tokens with a frame saying the provider did not answer. The provider was fine.
  - The log says so too: ERROR `Stream failed ... code=upstream_timeout` and the adapter's WARN `Provider stream failed ... TimeoutException`, for example `01a1a4be-e5c9-7922-98d2-6c93c44c2566`.
  - Section 5 accepts this ("a live client slow enough to starve upstream reads for `idle-timeout`"), and names `StreamStarvedUpstreamTest`. What is new is how wide it is: from 3 to about 17 KB/s at the defaults, against a stall limit of 3 KB/s. The systems entry's "a reader is safe down to one kernel step per timeout" holds only for the connection, and does not mention it.
  - It depends on `idle-timeout`, not on `client-write-timeout`. At idle 2 s, the `HANG=1` setting, the "160 KB/s held" of the stated limit does not hold: 146 KB/s got the frame on 3 of 3, and so did 114 and 205. Complete needs 683 KB/s there. The stated figure holds at idle 60 s.
  - Brackets measured: 11.8 to 19.7 KB/s at idle 60 s, 205 to 683 KB/s at idle 2 s. Inference, not measured: the demand refill of about 64 frames, 1 MB, must drain inside `idle-timeout`, so the edge scales with frame size. Ordinary frames were not affected in this run: the provider finished first.
  - The client also learns late. At 7.9 KB/s the server failed at 106 s and the frame reached the client at 305 s, behind the backlog. The upstream slot is free meanwhile (`client_cancelled`).
  - Suggested wording for the systems entry, the reviewer's call: state both limits. No code change is implied.
- **R2. "A token stream has no backlog to speak of" is true only for a client above the provider's rate** (low, documentation). A 984 B/s client on an ordinary 13 KB/s stream built one: 40888 bytes pending at the cut, at 76.8 s (`01a1a4be-fca2-72ab-95ec-dea85b8fc976`). The 3 KB/s limit applies to ordinary frames too.
- **R3. The stall line carries no request ID, and a non-stream cut leaves only that line** (info). `Closing connection b6b49b4e: no write progress, 8001059 bytes pending` is all the log holds at INFO for non-stream `01a1a4ce-cd6d-7fd7-bfb9-aa99794c9263`. On a stream `Stream cancelled` follows within the same second with the ID, and the two share no key. OBS-03's matter, as the systems entry says of the RST WARN.
- **R4. A provider that closes a pooled connection without saying so costs that client a 502** (info). Seen by accident: the fake provider closed 0.5 s after a complete response with no `Connection: close`, and the next stream on that connection failed with `status=null exception=WebClientRequestException durationMs=511`, 502 `upstream_error`, the request never reaching the provider, 3 of 14. It follows `disableRetry` and the comment on `max-idle-time`, and it is the correct row. Real keep-alive behaviour of a provider is unproven.
- **R5. Harness errors in this rerun, corrected** (info).
  - The fake provider's missing `Connection: close` (R4) made `notsse-first` pass on the wrong path, and failed `eof-nofinish` and `split-utf8`. Caught because the fake counted 11 requests of 14. Fixed, then 14 of 14.
  - The first attempt at that fix did not apply, so one run repeated the same result and is discarded.
  - `h_size.py`'s own H3 still measures without `include_usage`; `h3_boundary.py` replaces it, exact at 1024 and 1025.
  - Stopped clients at 30 s: 2 plus the kept-alive case, not 5, for time.
  - `finished` at 30 s slept 32 s, under the new window, so finding 3's cut is shown at 1 s only.
  - Block D at INFO shows 28 `Stream cancelled` of 50 race rounds and hides the rest, because `Stream completed` is DEBUG; the one-line check was shown at DEBUG in the first run, not repeated on this build.

**What the rerun still cannot show.** The first list stands, with these changes.

- Now shown over a real socket: CRLF, comments, split UTF-8, an in-band error first, EOF with and without `finish_reason`, an empty or non-SSE 200. Only through a fake: a real provider's framing stays unproven.
- Windows only, and the Revision of ADR-GW-02-009 says Linux differs: a bounded `SO_SNDBUF` held 32 KB/s there. Every rate above is a Windows loopback number. The kernel step sets the stall limit, so expect other numbers elsewhere.
- The bracket of R1 is between 11.8 and 19.7 KB/s, not a point, and was measured at 16 KB frames only. The rule that it scales with frame size is inferred. Frames near `max-event-size` were not exercised.
- 3.0 and 4.0 KB/s clients ended by this run's 470 s limit, so their frame was not read, only logged as failed.
- `ByteBuf` leaks: no `PARANOID` run here, and a plateau in NMT does not exclude a slow leak beyond 600 clients.
- Rates are paced by a Python client with `sleep`, so they are averages, and real clients read in other patterns.

## Revision (2026-11-04, naming)

The extension member shown above as `x_gateway` was renamed `llmgw` after this run (ADR-019).
