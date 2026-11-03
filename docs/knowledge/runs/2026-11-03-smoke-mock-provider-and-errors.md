---
id: runs/2026-11-03-smoke-mock-provider-and-errors
type: run
kind: smoke
title: First smoke run - mock-provider standalone (FND-03) and gateway-app request IDs and error envelope (FND-04), both via bootRun
status: active
build: fe7507a + uncommitted FND-03
area_tested: "FND-03 mock-provider (OpenAI and Anthropic routes, SSE timing, scenarios, faults, journal, reset); FND-04 gateway-app x-request-id, x-client-request-id, error envelope, log correlation"
verdict: pass-with-findings
tags: [smoke, mock-provider, request-id, errors, sse, reactor-netty, logging]
related: [systems/mock-provider, systems/request-context-and-errors, patterns/raw-sse-frames-flushed-per-event, bugs/committed-response-error-logged-twice-with-query-string, gotchas/spring-exception-handling-web-handler-checkpoint-carries-raw-query]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiHandlers.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorWebExceptionHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextWebFilter.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/GatewayErrorWebExceptionHandler.kt
  - gateway-app/src/main/resources/application.yaml
---

## What was run

- **Stack:** no compose stack (FND-02 not built). `./gradlew :mock-provider:bootRun` (:8090, PID 33476) and `./gradlew :gateway-app:bootRun` (:8080) on Windows 11, Java 25.0.4.1, Spring Boot 4.1.2. Both started in about 1.6 s. gateway-app ran first with default config (PID 40240), then was restarted with `LOGGING_LEVEL_IO_GITHUB_PDROACORDI_LLMGATEWAY=DEBUG` (PID 11928) because 4xx are logged only at DEBUG. No source or config file was changed.
- **Mock settings:** defaults (0 ms latency), `POST /__mock/reset` first. Latency came from scenarios: `mock-chat-mini` `ttftMs 300, perTokenMs 200, completionTokens 5`; `mock-claude` `ttftMs 100, perTokenMs 150, completionTokens 4`. Credentials were dummy strings, never a provider key. No real provider was called.
- **Tools:** curl 8.18.0 (`-N` for streams) and a Python wrapper that timestamps each SSE line from request start. Raw outputs are in the session scratchpad, not the repo.
- gateway-app has no product endpoint (GW-01), so its scenarios cover FND-04 only. The mock was not reached through the gateway.

## Results

| # | Scenario | Expected | Observed | Result |
|---|---|---|---|---|
| G1 | `GET :8080/actuator/health` x5 | 200, UUIDv7 `x-request-id`, distinct | 200; 5 distinct IDs match `^…-7…-[89ab]…$`; `01a1a2ad-1a0b-…` decodes to 18:30:15.819Z = log time | pass |
| G2 | `GET /v1/does-not-exist` | 404, `application/json`, exactly `{error:{message,type,param,code}}` | 404, `Content-Type: application/json`, `x-request-id`, `{"error":{"message":"Unknown request URL: GET /v1/does-not-exist.","type":"invalid_request_error","param":null,"code":"unknown_url"}}`; 1 top-level member, 4 inner | pass |
| G3 | request `x-request-id: client-chosen` | replaced | response `x-request-id: 01a1a2ad-43f4-797d-…` | pass |
| G4 | `x-client-request-id: abc-123` on 200 and 404 | echoed | echoed on both | pass |
| G5 | 513-char / 512-char `x-client-request-id` | 400 `invalid_client_request_id` / 200 | 400 envelope + `x-request-id` / 200, echo length 512 | pass |
| G6 | header sent twice; empty value; tab; byte 0xE9 | 400; absent; 400; 400 | 400 envelope; 200 with no echo; 400 envelope; 400 envelope | pass |
| G6c | DEL (0x7F) or 0x01 in a header value | (not in spec) | Netty 400 before WebFlux, empty body, no `x-request-id` (finding 3) | info |
| G7 | `POST` / `DELETE /actuator/health` | 405 `method_not_allowed` + `allow` | 405, `allow: GET`, envelope, `x-request-id` | pass |
| G8 | `GET /nope?secret=1`, logs | `[<id>]` in log, no query | DEBUG `[01a1a2ae-53ee-…] … Request rejected with status 404 (unknown_url)`; same ID as header; 405 and 400 lines likewise; `secret`/`token=`/`k=v` in all 3 gateway logs: 0 | pass |
| M1 | OpenAI chat non-stream, same request x2 | same content and usage, different `id` | identical content, usage 12/16/28; `chatcmpl-mock-000000000001` vs `…002`; one-char change and model change both change the content | pass |
| M2 | OpenAI stream, `include_usage`, `perTokenMs 200` | `data: ` framing, usage chunk, `[DONE]`, spread over time | 9 events, all `data: …\n\n`, 0 CR bytes; role chunk + piece 1 at 358 ms (ttft 300); pieces 2-5 and finish 201.5-203.5 ms apart; usage chunk `choices: []` at 1371.7 ms; `data: [DONE]` last; `usage: null` on every earlier chunk | pass |
| M2b | same request non-stream; stream without `include_usage` | same text and usage; no `usage` member | same text and 10/5/15, body after 1315 ms (300 + 5 x 200); 8 events, 0 `usage` members | pass |
| M3 | Anthropic `/v1/messages` non-stream x2 (`x-api-key`) | deterministic, Anthropic shape | same text and usage, `msg_mock_…08` / `…09`, `request-id` header; `input_tokens` 16 = OpenAI `prompt_tokens` of the same conversation (and same text) | pass |
| M4 | Anthropic stream | documented event order | `message_start, content_block_start, ping, content_block_delta x4, content_block_stop, message_delta, message_stop`; deltas 155.0-157.5 ms apart at `perTokenMs 150`; `message_delta.usage = {output_tokens: 4}` | pass |
| M5 | embeddings float / base64 / batch / empty | norm 1, base64 = LE float32 | 256 dims, norm 1.0000000, same input same vector; base64 1024 bytes decode to the float vector with max diff 0.0; batch default 1536 dims, indexes 0,1; `""` gives 400 `param: input` | pass |
| M6 | `GET /v1/models` | 4 models, `owned_by: mock-provider` | `mock-chat`, `mock-chat-mini`, `mock-claude`, `mock-embed` | pass |
| M7 | no credential on models, chat, messages; no key + bad body | 401 OpenAI / Anthropic shape; 401 before 400 | 401 `invalid_api_key` x2; 401 `authentication_error` with `request_id` = `request-id` header; bad body 401 | pass |
| M8 | 429 step `times 1` + 10 concurrent | 1 x 429 with `retry-after`, 9 x 200 | 1 x 429 (`Retry-After: 2`, `x-ratelimit-remaining-requests: 0`, `type: requests`, `code: rate_limit_exceeded`), 9 x 200; `lastSeq` 22 to 32 (delta 10) | pass |
| M9 | 503 `times 2`, sequential; Anthropic 429 `retryAfterMs 1500` | 503, 503, 200; Anthropic shape | 503 `server_is_overloaded` x2 then 200; 429 `rate_limit_error`, `retry-after-ms: 1500`; `stepsConsumed` 2 and 1 | pass |
| M10 | `stall` after 2 pieces, client gives up at 2 s | `client_cancelled`, `piecesSent: 2` | curl exit 28 at 2084 ms with 3 events (role + 2), no `[DONE]`; journal `client_cancelled`, `piecesSent 2`, `durationMs 2009`, `inFlight 0` | pass |
| M11 | `disconnect` stream (after 3) and non-stream | premature close | stream: 200, 4 events, curl exit 18, `aborted`, `piecesSent 3`; non-stream: 200, 287 body bytes, curl exit 18, `aborted`. One ERROR log line per abort (finding 1) | pass-with-finding |
| M12 | `stream_error` after 1 piece | in-band error, no `[DONE]` | `data: {"error":{…500…,"type":"server_error","param":null,"code":null}}`, end, `stream_error` outcome | pass |
| M13 | journal credentials | scheme + 8-hex SHA-256 only | bearer `4143e64d` (34 entries), x-api-key `b402b269` (4), both = SHA-256[:8] of the dummy keys; every `authorization` / `x-api-key` value `["[redacted]"]`; key strings in journal, stats, scenarios and all 3 logs: 0 | pass |
| M14 | admin validation (negative ttft, status 399, kind `explode`, `times 0`, member `stepz`) | 400 with `param`, store unchanged | 5 x 400, `param` names the member; stored `mock-chat` scenario unchanged | pass |
| M15 | unknown paths `POST /v1/x?secret=…`, `/v1/messages/batches` | 404 by shape, journaled `unknown` | OpenAI 404 / Anthropic `not_found_error`; journaled seq 43, 44 route `unknown`; query not in log | pass |
| M16 | `header=` filter, reset | one match; empty after, `lastSeq` kept | `header=X-Client-Request-Id:burst-3` returns 1 entry; reset gives `lastSeq 44`, empty counts, scenarios and journal; next request is seq 45 | pass |

Rejected requests' `model` field (finding 2) was noticed in M13's counts.

## Findings

1. **mock-provider: every intentional abort writes one Reactor Netty ERROR line** (low). Each `disconnect` fault (3 of 3) logs `ERROR … r.n.http.server.HttpServerOperations : [881a1482-1, L:…:8090 - R:…:64519] Error finishing response. Closing connection` followed by `MockConnectionAbort: disconnect fault`. The line has no request ID and no query string (`?secret=abort-q-9` absent). The systems entry says the mapper logs aborts at DEBUG and `HttpWebHandlerAdapter` is `off`, but this logger is a third one, below WebFlux. Load runs with abort faults will fill the log with ERROR lines, and a test asserting "no ERROR" on a real socket will fail. Relevant to gateway-app too: FND-04 checked "one ERROR line after commit" through a mock exchange, so a real socket there would probably show this second line without the ID (GW-02 should confirm). A `stall` reaching `mock.max-hold` takes the same path but was not exercised (300 s).
2. **mock-provider: a request rejected by body validation is journaled with `model: null`** (low). `POST /v1/embeddings {"model":"mock-embed","input":""}` gives 400 and journal seq 15 `model: None`, body intact, counted under `(openai.embeddings, None)`. `record.model` is set only after `OpenAiRequests.chat/embeddings(body)` validates the whole body (`OpenAiHandlers.kt:30`, `:60` in the build tested). As a result, `/__mock/requests?model=<m>` and per-model counts miss a model's 400s, and tests are told to isolate by model name. Contract 4 (one entry per request) still holds. The 401 cases (`model: null`, body not read) are by design. Fixed after this run: `RequestRecord.noteModel` (`mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Inbound.kt:44`) records the body's `model` before validation, called at `OpenAiHandlers.kt:30`, `:61` and `AnthropicHandler.kt:24`; test `JournalIntegrationTest:122`.
3. **gateway-app: a control character in any header value never reaches WebFlux** (info, gotcha candidate). `x-client-request-id: ab<0x7F>` (or `x-other: ab<0x01>`) gives `400`, `content-length: 0`, `connection: close`, no envelope, no `x-request-id`, plus one `WARN r.n.http.server.HttpServerOperations … Decoding failed: … Validation failed for header 'x-client-request-id'` with the request line `GET /v1/nope?<filtered> HTTP/1.1` (Netty filters the query and header values; the path and header name are logged). The systems entry lists only a malformed request line, a long initial line and a large header as Netty-rejected. Tab and byte 0xE9 do reach the filter and get the `invalid_client_request_id` envelope. Any client can produce one WARN line per request this way.
4. **gateway-app logs nothing for 4xx at the default level** (info, by design). The `[<request-id>]` correlation was proven only with DEBUG for `io.github.pdroacordi.llmgateway`. Only `health` is exposed under `/actuator`, so the level cannot be raised at runtime.

Harness note: the first `stall` attempt was invalid. A second client I started in parallel took the `times: 1` step, so the measured request ran normally. The re-run with one client (M10) passed, and `inFlight` was 0 afterwards.

## What this run cannot show

- Gateway behaviour in front of a provider: no `/v1` endpoint before GW-01, so there is no proxying, header forwarding, or SSE passthrough or buffering through the gateway. The cache/guardrail `lastSeq` checks have no subject yet.
- Real provider latency, errors and framing drift: only the mock was called.
- gateway-app's 5xx path (ERROR line with stack trace and request ID, message ending in the ID) and 413/415/406: they need a route that reads a body or throws, which is test-only today (`/test-probe`).
- Multi-replica effects (one process each), load behaviour (single requests and one 10-request burst), and the DRBG lock contention noted in the systems entry.
- FND-03 section 8 steps 6-7 (`java -jar` and the Docker image), `hang`, `malformed`, `usage.omit`, and holds reaching `mock.max-hold` (300 s): not run here.
- Linux/container networking: Windows loopback only. curl and Python timing adds about 50 ms to the first byte, so the ttft figures are upper bounds.
