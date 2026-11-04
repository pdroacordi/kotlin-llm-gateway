---
id: runs/2026-11-04-smoke-anthropic-adapter
type: run
kind: smoke
title: Anthropic adapter end to end against mock-provider and a scripted stub - GW-03 section 8 scenarios, status and in-band tables, tools, thinking, usage, starts, OpenAI regression
status: active
build: fd3f764 + uncommitted GW-03
area_tested: "GW-03 Anthropic adapter on POST /v1/chat/completions, provider selection and startup checks, tools/smoke/anthropic.sh, OpenAI path after the extraction to .upstream (tools/smoke/chat-completions.sh, tools/smoke/streaming.sh)"
verdict: pass-with-findings
tags: [smoke, gw-03, anthropic, tools, thinking, sse, usage, startup, regression]
related: [systems/provider-adapters, systems/mock-provider, systems/request-context-and-errors, runs/2026-11-04-smoke-sse-streaming, runs/2026-11-03-smoke-chat-completions, patterns/member-policy-table-for-a-foreign-provider, patterns/typed-core-with-json-extras, gotchas/conditional-on-property-and-the-binder-read-true-differently]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/anthropic.sh
  - tools/smoke/chat-completions.sh
  - tools/smoke/streaming.sh
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/json/JsonTrees.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/anthropic/AnthropicMemberPolicy.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderConfiguration.kt
---

## Verdict

`pass-with-findings`. Every section 8 scenario and every row of the status and in-band tables holds over HTTP, the OpenAI path did not regress, and no check found a 5xx, a leak or a wrong status. Three low findings stand, all on inputs at the edge of the contract; finding 1 is a literal reading of an acceptance criterion and is the only one the spec states outright. `tools/smoke/anthropic.sh` ran first time and exited 0, but it asserted far less than its header claimed; it was corrected in this run (finding 4).

## What was run

- **Stack:** no compose stack. `docker compose -f deploy/compose.yaml ps` was empty before and after. GW-03 calls no Redis, Postgres or broker, emits no span and has no admin API (spec section 5, Data, Observability, Failure model), so `stack-check.sh`, seeding, auth, Jaeger, ledger and broker checks do not apply, as in the GW-02 run.
- **Runtime:** Windows 11, Spring Boot 4.1.2, Java 25.0.4.1, Reactor Netty 1.3.8. `bootJar` of both apps run with `java -jar`, so each process could be stopped by the PID on its port. Ports 8080 and 8090 were free before the run.
- **mock-provider:** defaults, `POST /__mock/reset` before each block; stopped and restarted once for the stopped-provider check.
- **Scripted stub:** a stdlib Python HTTP server on 127.0.0.1:18091 in the session scratchpad, for wire shapes the mock cannot produce (unreadable usage, unknown events, malformed frames, a held body). Used by start 4 only, through `LLMGW_PROVIDERS_ANTHROPIC_BASE_URL`.
- **No real provider:** `ANTHROPIC_BASE_URL` was set in the shell and was removed from every start, though the gateway does not read it; `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` were removed too, and `.env` was never read. `api.anthropic.com` and `api.openai.com` appear 0 times in all logs.
- **Tools:** the three scripts, a stdlib Python raw-socket client that keeps chunk arrival times and checks the chunked terminator, curl.
- **Beside this run:** four containers of another project, untouched. Treat latency as noisy.

| Start | Settings | Used for |
|---|---|---|
| 1 | `local`, DEBUG on our package | `anthropic.sh` as written, `chat-completions.sh`, `streaming.sh` |
| 2 | first byte and idle 2 s on both providers, request 2 s, client write 1 s | all three scripts with `HANG=1` |
| 3 | `local`, DEBUG | member, stream, tool, thinking, status and in-band checks |
| 4 | Anthropic base URL at the stub, prefixes `u-,s-,b-,x-,st-` | usage and stream wire shapes |
| 5, 7 | DEBUG, then root DEBUG with `web` TRACE | disconnects, log canaries |
| 6 | INFO | latency |
| 8 | total, first byte, idle and request 1 s, client write 500 ms | the deadlines |
| 9 | `local` | stopped provider |
| 10 | Anthropic off, its key blank, its version `latest`, its max tokens 0, its idle 1 s | OpenAI-only, negative control of the script |
| 11 | no profile, no Anthropic setting, fake `OPENAI_API_KEY`, OpenAI URL at the mock | default configuration |
| 12 | OpenAI off, default provider `anthropic`, OpenAI key blank | Anthropic-only |
| 13, 14 | `local`; then first byte and idle 2 s, client write 1 s | corrected `anthropic.sh` |
| 15 | `local`, DEBUG | edge members, mixed concurrency, number literals |
| 14 refused or probed starts | see the startup table | startup checks |

## Results

Script counts:

| Script | Start | PASS | SKIP | FAIL | Exit |
|---|---|---|---|---|---|
| `anthropic.sh` as written | 1 | 67 | 2 (9, 10) | 0 | 0 |
| `HANG=1 anthropic.sh` as written | 2 | 69 | 1 (9) | 0 | 0 |
| `anthropic.sh` corrected | 13 | 182 | 2 (9, 10) | 0 | 0 |
| `HANG=1 anthropic.sh` corrected | 14 | 184 | 1 (9) | 0 | 0 |
| `anthropic.sh` corrected, negative control on an OpenAI-only start | 10 | 109 | 2 | 73 | 1 |
| `chat-completions.sh` | 1 | 59 | 3 (6, 7, 12) | 0 | 0 |
| `HANG=1 chat-completions.sh` | 2 | 61 | 2 (7, 12, manual) | 0 | 0 |
| `streaming.sh` | 1 | 64 | 1 (8) | 0 | 0 |
| `HANG=1 streaming.sh` | 2 | 68 | 0 | 0 | 0 |

The regression counts equal the last runs: `chat-completions.sh` 59 and 61, `HANG=1 streaming.sh` 68. Median added latency on the OpenAI path in the script: 3.25 ms (start 1), 2.86 ms (start 2).

By hand, 1342 asserted checks in the final state, 0 failed, plus 22 read by eye (deadlines, stopped provider, starts). Harness errors on the first pass, all corrected and none a product fault: `Host: x` is a 403 from `LoopbackHostWebFilter`, `stream_options` without `stream` is GW-02's 400, every chunk carries `usage: null` under `include_usage` as on OpenAI, and Python's `1.10` is `1.1`.

| # | Scenario | Expected | Observed | Result |
|---|---|---|---|---|
| 1 | `mock-claude` non-stream | section 4 reply, journal headers | 200; `x-gateway-deployment: anthropic`, UUIDv7 `x-request-id`, client ID echoed; exact `choices[0]`, `refusal: null`, `logprobs: null`; journal `anthropic.messages`, `/v1/messages`, one entry, `x-api-key` fingerprint `b9a55896` = SHA-256 of the configured key, `anthropic-version: 2023-06-01`, no `authorization`, `x-client-request-id` = gateway ID; body exactly `model`, `max_tokens: 4096`, `messages` | pass |
| 2 | Client headers | none upstream | `Authorization`, `x-api-key`, `anthropic-version`, `x-custom` from the client absent or replaced; no `request-id` to the client | pass |
| 3 | Selection | prefix table | `claude-x`, `mock-claude` anthropic; `mock-chat`, `Claude-x` openai | pass |
| 4 | Stream | framing, spread | 13 frames in 16 socket reads, first at 237 ms with ttft 200, last at 1449 ms with `perTokenMs` 100; `text/event-stream`, `x-accel-buffering: no`, one `id`, one `created`, exact member set, `role` once, `[DONE]`, chunked terminator read; journal `accept: text/event-stream`, `stream: true` | pass |
| 5 | `include_usage` | one final chunk | true: one chunk with empty `choices`; false or absent: none; `[DONE]` in each; `stream_options` not upstream | pass |
| 6 | Cached usage | 60, 5, 65, 20, 30 | `prompt_tokens` 60, `completion_tokens` 5, `total_tokens` 65, `cached_tokens` 20, `cache_creation_input_tokens` 30, `reasoning_tokens` 0, equal on both forms | pass |
| 7 | Tools, both forms | OpenAI shape out, Anthropic shape in | reply `tool_calls` with `finish_reason: tool_calls`; `tools` as `input_schema`, `strict` kept; second turn exactly assistant `text` then `tool_use`, then user `tool_result` with `tool_use_id: toolu_1`; `"n":1.10` kept; no extra turn | pass |
| 8 | Parallel tools | merged, indexes from 0 | two `tool` plus `user`: one user message, both results first; stream indexes 0, 1 with Anthropic's 1, 2; arguments concatenate; no empty argument chunk; no input ends `{}`; tool-only first delta `role`, `content: null` | pass |
| 9 | `tool_choice`, `parallel_tool_calls` | table | `auto`, `none`, `any`, `tool`; `false` sets `disable_parallel_tool_use`, not with `none` | pass |
| 10 | Mock's conversation check | bites | user turn with no result after `tool_use`: 400 `upstream_rejected_request` | pass |
| 11 | Thinking | blocks out and back | `message.llmgw.thinking_blocks` and the finish delta equal, once, nothing in `content`; echoed: journal order `thinking`, `redacted_thinking`, `text`, `tool_use`, blocks equal, no `llmgw`; `cache_control` and strays cut by the allow-list; a `tool_use` block in the list 400, no call | pass |
| 12 | `llmgw` to OpenAI | never | top-level and message `llmgw` on `mock-chat`: absent from the journal body | pass |
| 13 | Reject rows | 400, `code`, `param`, no call | 22 members on both forms exact, `lastSeq` unchanged over all 44; `bad name!` gives `param: null`, name not echoed | pass |
| 14 | Status table | section 4 | 22 rows on both forms, table below; provider message absent; one journal entry each | pass |
| 15 | In-band before the first frame | the row's status | 13 types, table below; JSON, not SSE; mock verified to send 200 then `event: error` on the wire | pass |
| 16 | In-band after the first frame | one frame | 13 types: 200, content first, exactly one error frame last, no `[DONE]`, chunked terminator read, no provider message | pass |
| 17 | Thinking prefix then error | status | 503 `provider_unavailable`, no signature, no thought (mock and stub) | pass |
| 18 | Disconnect | `client_cancelled` within 2 s | mid-stream 3 to 27 ms, `piecesSent` 11 of 200; `hang` on stream 4 ms and complete 7 ms; one `Stream cancelled` line with the request ID, `chunks=11` | pass |
| 19 | Usage omitted | flagged | `x-gateway-usage-estimated: true`; stream `llmgw: {"usage_estimated": true}` | pass |
| 20 | Unreadable usage, stub | flagged | string `input_tokens`, string or missing `output_tokens`, string or object `cache_*`, sum past `Long`: 200, content kept, estimate 7 and 2, flag set; on a stream the same; null cache counts 0, unflagged; a bad count followed by a readable one unflagged, 4; `message_start`'s 99 never used | pass |
| 21 | Wire shapes, stub | section 4 stream table | `ping` everywhere, unknown events and deltas change nothing; 7-byte body splits; `message_stop` then a held body ends at 1.08 s; close after `message_delta` completes with usage; close before it gives `upstream_error` as a frame, or 502 before content; no `stop_reason` finishes `stop`; `content_block_delta` first and bad JSON first 502, bad JSON later a frame; thinking assembled `PRIVATE-THOUGHT more`, `sig/+=A`; empty answer's first chunk is the finish chunk with `role` | pass |
| 22 | Stop reasons | table | `stop_sequence` stop, `refusal` content_filter, `pause_turn` and `context_window` length, on both forms; null, absent and unknown stop; cap length | pass |
| 23 | Deadlines | `upstream_timeout` | first byte 504 at 1.01 s; request 504 at 1.02 s; idle frame at 1.01 s; total frame at 1.06 s after 10 chunks; each with no `[DONE]`, clean end, mock `client_cancelled` | pass |
| 24 | Stopped provider | 503 | 503 `provider_unavailable` on both forms; 200 once the mock was back, with no gateway restart | pass |
| 25 | OpenAI-only, start 10 | `openai.chat` | `claude-x`, `mock-claude`, `mock-chat` on both forms: `x-gateway-deployment: openai`, `/v1/chat/completions`, bearer credential; four bad Anthropic values stopped nothing | pass |
| 26 | Default configuration, start 11 | as today | same, on profile `default`, no Anthropic setting | pass |
| 27 | Anthropic-only, start 12 | default provider anthropic | all three models on `anthropic.messages`, `x-api-key` | pass |
| 28 | Log canaries, start 7 | none | prompt, system, tool description, arguments, result, thinking, signatures, reply, `user` and its hash, provider message and low-case code: 0 in 2713 lines (1523 DEBUG, 184 TRACE); `mock-local-key` 0 | pass |
| 29 | Edge members | never 5xx | 107 shapes on both forms, 214 requests: 0 5xx, every 4xx in the envelope, both forms agree on all | pass with findings 2, 3 |
| 30 | Mixed load | no error | 2000 requests at concurrency 32, 3/4 Anthropic, half streams: 0 bad, 1028 req/s, p50 13.6 ms, p99 214 ms, journal 1500 and 500 all `completed`, 0 `LEAK` lines | pass |
| 31 | `temperature` literal | same literal | `0.20`, `1.0` kept; exponent forms not | fail, finding 1 |

Status table, equal on `complete` and on a stream before its first frame:

| Upstream | Client | Retry headers | `x-should-retry` |
|---|---|---|---|
| 400 | 400 `upstream_rejected_request` | none | none |
| 401, 403 | 502 `upstream_auth_failed` | none | false |
| 402 | 503 `upstream_quota_exhausted` | none | false |
| 404 `not_found_error` | 404 `model_not_found` | none | none |
| 404 other type | 502 `upstream_error` | none | none |
| 408, 504 | 504 `upstream_timeout` | none | none |
| 409, 418, 500 | 502 `upstream_error` | none | none |
| 413, 422 | same status, `upstream_rejected_request` | none | none |
| 429 | 429 `upstream_rate_limited` | none | none |
| 429 with `retry-after: 7` | 429 | `retry-after: 7`, `retry-after-ms: 7000` | none |
| 429 with `retry-after-ms: 1500` | 429 | `retry-after: 2`, `retry-after-ms: 1500` | none |
| 429 `enforced_spend_limit_reached` | 503 `upstream_quota_exhausted` | none | false |
| 500 `timeout_error` | 504 `upstream_timeout` | none | none |
| 503, 529 | 503 `provider_unavailable` | none, or relayed when sent | none |

In-band, a status before the first frame and the same `code` in a frame after it: `overloaded_error` 503 `provider_unavailable`; `rate_limit_error` 429 `upstream_rate_limited`; with `enforced_spend_limit_reached` and `billing_error` 503 `upstream_quota_exhausted`; `authentication_error`, `permission_error` 502 `upstream_auth_failed`; `invalid_request_error` 400 and `request_too_large` 413 `upstream_rejected_request`; `not_found_error` 404 `model_not_found`; `timeout_error` 504 `upstream_timeout`; `api_error`, `conflict_error`, an unknown type 502 `upstream_error`.

Startup, each with the local profile:

| Setting | Result |
|---|---|
| `anthropic.enabled=yes`, `1`, `on`; `openai.enabled=yes` | exit 1, `llmgw.providers.<provider>.enabled must be written true or false: its value and the adapter disagree.` |
| `anthropic.enabled=maybe` | exit 1, Spring's bind failure, which prints `Value: "maybe"` |
| `anthropic.enabled=TRUE` | starts: binder and condition agree on it, so nothing to refuse |
| Blank key; non-ASCII key | exit 1, naming `ANTHROPIC_API_KEY`; no key value in the log |
| Both providers off | exit 1, `No provider is enabled` |
| `openai.enabled=false`, or Anthropic off with default `anthropic` | exit 1, `default-provider must name an enabled provider` |
| `version=latest` | exit 1, `must be a date, YYYY-MM-DD` |
| `default-max-tokens=0` | exit 1, `must be positive` |
| `client-write-timeout` equal to Anthropic's idle | exit 1, names the property and both idle properties |
| `total-timeout=1s` with first byte at 120 s | exit 1, `first-byte-timeout must not exceed stream.total-timeout` |

Latency, 4 KB request, keep-alive, 1000 per side in interleaved rounds, start 6:

| Path | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| Gateway, Anthropic | 1.18 | 2.10 | 3.98 |
| Gateway, OpenAI | 1.15 | 2.19 | 3.11 |
| Direct, `/v1/messages` | 0.28 | 0.49 | 1.01 |
| Direct, `/v1/chat/completions` | 0.26 | 0.43 | 0.76 |

Added at p50: 0.90 ms on Anthropic, 0.89 ms on OpenAI; target within 1 ms, met. First frame at `ttftMs` 50: gateway p50 55.49 ms, p95 60.10 ms; OpenAI 55.08 ms; the mock's own first `text_delta` direct, p50 53.44 ms. So 2.05 ms added to what the mock delivers, and 5.49 ms over the nominal 50, against a target of 5 ms over `ttft`: met against the measured mock, by Windows timer slack just over against the nominal.

## Findings

### 1. Exponent-form numbers lose their literal (low)

Section 3: `temperature` and `top_p` sent "are there with the same literal"; section 4: "Same name, same literal", and for tool arguments "Numbers keep their literal".

```
POST /v1/chat/completions {"model":"mock-claude",...,"temperature":<literal>}
journal body: 1e-1 -> 0.1 | 2.50E-1 -> 0.250 | 1E0 -> 1 | 1e2 -> 1E+2
tool argument {"a":1e2} -> "input":{"a":1E+2}; 1e-3 -> 0.001; 1e999 -> 1E+999
```

Status 200 throughout. The value is equal, and precision holds: `0.20`, `1.10`, `0.1000000000000000055511151231257827` and 30-digit integers pass unchanged. `mock-chat` gives the same result, so this comes from the shared `BigDecimal` tree (`JsonTrees.kt:20,70`), and is not a regression of the extraction. Either render the original text, or narrow the spec to "the same value and precision". A provider that wants an integer could read `1E+2` differently from `100`.

### 2. A non-boolean `parallel_tool_calls` is dropped silently (low)

`{"tools":[...],"parallel_tool_calls":"x"}` gives 200, and the journal body has no `tool_choice`. The table covers `false` and `true` only; ADR-GW-03-005 rejects silent drops, and `stop` of another type is `unsupported_value`. Expected the same here. `lastSeq` advanced by 1.

### 3. `llmgw.thinking_blocks: null` is a 400, not absent (low)

`{"role":"assistant","content":"b","llmgw":{"thinking_blocks":null}}` gives 400 `invalid_request`, `param: messages[1].llmgw`. Section 4: "any member set to `null` counts as absent"; `llmgw: null` itself is accepted on the message and at the top level. Whether the sentence reaches inside the extension is the spec's call; the code and the sentence disagree as written.

### 4. `tools/smoke/anthropic.sh` asserted less than it claimed (script, fixed here)

It ran with no error, its `HANG=1` header is right, and a negative control proves it can fail. What it really did, and what changed:

- Scenario 5 ran 7 of the 17 status rows and took every in-band row from the mock's default type per status, checking only the status. A shared row list cannot serve both tables: an in-band 408 is `invalid_request_error`, so 400, not 504. Now two tables, 17 status rows and 13 in-band types, each with its `code`, the frame after commit, and `retry-after` with and without.
- Scenario 7's `"content":"sig-1` could never match, so that check could not fail. Now `"content":"Hi"`, and the journal check asserts block order.
- Scenario 2 grepped the bare words `tool_use` and `tool_result`, which order and IDs do not affect. Now the exact escaped messages.
- Scenario 6 had no `error` member, so it rested on the fault's default. It sets 529 and checks the code.
- Scenario 1 never showed frames arriving apart, though section 8 asks for it. It now compares first byte with the total at `perTokenMs` 100, and reads `max_tokens` and the credential scheme.
- Scenario 10 said three deadlines and ran two. Comment corrected; total-timeout needs its own start and was run by hand. Scenario 8's expected curl timeout no longer prints a line that reads like a failure.

### Observations, not defects

- The mock's `toolCalls[].input` loses a literal (`1.10` becomes `1.1`, seen directly on `/v1/messages`), though ADR-GW-03-019 says "kept as compact text". The gateway keeps the literal on the reply (stub: `1.10` and `12345678901234567890.5` byte-equal), so a test of the reply literal through the mock cannot pass.
- `tool_choice` with no `tools`, tools with no name or a numeric name, `user` content null (sent as `""`), an empty `stop`, empty assistant content: forwarded, a provider 400 relayed, which is section 4's stance. The mock accepts them all, so the relay is unproven for these.
- Local `.env` names `ANTHROPIC_KEY`; the gateway and `.env.example` use `ANTHROPIC_API_KEY`. It matters only for a future live check.
- `enabled=maybe` prints the value through Spring's failure analyser, against a strict reading of "naming properties only". Harmless for a boolean.

## What this run cannot show

- **Real Anthropic behaviour:** L1 to L4 of section 8 stay unproven: omitted thinking in a tool turn, replay placement, the real 404 body, TLS and real `event: error` bodies. The mock and the stub are this project's reading of the docs. Real latency and rate limits are not seen.
- **Relayed provider 400s** for shapes the mock accepts (finding list above), forced `tool_choice` and sampling on models that refuse them.
- **Multiple replicas:** one gateway, one mock. **Load:** 2000 requests at concurrency 32 is a smoke, not a profile, and ran at DEBUG; no soak, no slow-client round on this path.
- **Not run by hand:** held thinking blocks over `max-event-size`; a client that stops reading on this path (GW-02's check is shared code); `openai-java`, BlockHound and PARANOID leak detection, which are in the integration suites, not here. This run saw 0 `LEAK` lines without PARANOID.
- **Spans, cost, limits, cache, guardrails:** not built on this path.

## Revision (2026-11-04)

What the closing round did with the findings. Finding 1: corrected in the spec, with no code change: the value and precision are kept, the literal may normalise, on both adapters ([gotcha](../gotchas/json-number-literals-normalise-through-big-decimal.md)). Finding 2: fixed, a non-boolean `parallel_tool_calls` is 400 `unsupported_value`, with or without `tools` (`AnthropicToolWriter.write`). Finding 3: fixed, `llmgw.thinking_blocks: null` counts as absent (`AssistantBlockWriter.thinking`). Both have unit tests that went red first; this smoke was not rerun over HTTP after them. The mock's literal observation is a [gotcha](../gotchas/mock-scripted-tool-input-normalises-number-literals.md), and the `.env` naming is in roadmap GW-11.
