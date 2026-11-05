---
id: runs/2026-11-05-smoke-tools-and-response-format
type: run
kind: smoke
title: GW-06 tool calling and response_format against mock-provider and compose Postgres - OpenAI tool conversations, Anthropic output_config, refusal rows, tool-only estimates, log canaries; first execution of the new script checks
status: active
build: fb1ff4f + uncommitted GW-06
area_tested: "GW-06 OpenAI tool pass-through, response_format per adapter, client output_config, tool output in the estimator, logs at INFO and web debug; regression: chat-completions.sh, anthropic.sh, streaming.sh, embeddings.sh"
verdict: pass-with-findings
tags: [smoke, gw-06, tools, response-format, output-config, usage-estimate, canary, regression]
related: [systems/provider-adapters, systems/mock-provider, patterns/typed-core-with-json-extras, gotchas/json-number-literals-normalise-through-big-decimal, bugs/smoke-text-format-check-passes-on-an-empty-journal, runs/2026-11-05-smoke-embeddings, runs/2026-11-04-smoke-anthropic-adapter]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - tools/smoke/chat-completions.sh
  - tools/smoke/anthropic.sh
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/anthropic/AnthropicResponseFormatWriter.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/UsageEstimator.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/chat/StreamTally.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiRequests.kt
---

## Verdict

`pass-with-findings`. 1,077 checks passed and 0 failed on the final form of each check: 647 in the four scripts, 2 skipped, and 430 by hand. The gateway showed no defect against the brief. The new script checks passed on their first execution, but three of them could pass on empty or wrong values, and were corrected (findings 1 to 3). Findings 4 to 6 are differences between the spec's words and the running system.

## What was run

- **Stack:** only `postgres` from compose (`postgres:18.6-trixie`), started by this run and stopped after it; `ps -a` showed it exited before the run. `stack-check.sh` was not run: GW-06 uses no Redis, broker or span.
- **Runtime:** Windows 11, Java 25.0.4.1, gateway at `-Xmx2g`. `bootJar` up to date with the tree (15 of 15 tasks), jars copied to the scratchpad and run with `java -jar`.
- **mock-provider:** defaults, reset by each script and hand block. No paid call: `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` were removed from every start, and `api.openai.com` and `api.anthropic.com` appear 0 times in the logs.
- **Tools:** the four scripts; a stdlib Python driver over `http.client`, keys in process memory only.
- **Beside this run:** four `candidatus-*` containers, untouched. A reviewer wrote `bugs/smoke-text-format-check-passes-on-an-empty-journal` during the run, by reading; no other run was seen on the stack.
- **Tree:** production files unchanged. Of the modified and untracked files, only the two scripts, that bug entry and `INDEX.md` are newer than the start of the run.

| Start | Settings | Used for |
|---|---|---|
| B, B2 | `local`, `LOGGING_LEVEL_WEB=debug`, 2 s deadlines, client write 1 s | the four scripts with `HANG=1` and `GATEWAY_LOG`, as found and fixed; the mutations |
| A | `local`, INFO | first hand run |
| D | `local`, `LOGGING_LEVEL_WEB=debug` | hand run, 429 PASS, 0 FAIL, canaries |
| A2 | `local`, INFO | final hand run, 430 PASS, 0 FAIL, canaries, latency |

## Results

| Script | PASS | SKIP | FAIL | Exit | Last run |
|---|---|---|---|---|---|
| `HANG=1 chat-completions.sh` as found | 103 | 1 (7) | 0 | 0 | 60 |
| `HANG=1 anthropic.sh` as found | 222 | 1 (9) | 0 | 0 | 190 |
| `HANG=1 chat-completions.sh` fixed | 107 | 1 (7) | 0 | 0 | |
| `HANG=1 anthropic.sh` fixed | 229 | 1 (9) | 0 | 0 | |
| `HANG=1 streaming.sh` | 68 | 0 | 0 | 0 | 68 |
| `HANG=1 embeddings.sh` | 243 | 0 | 0 | 0 | 243 |

The SKIPs are the stopped mock and the OpenAI-only start, which need a start of their own. GW-06 changes neither, and they were not run by hand.

The first hand run had 6 FAILs, all an error of the check: it asked for `llmgw` on a non-stream reply, where the flag is the `x-gateway-usage-estimated` header and `llmgw` belongs to the stream's usage chunk (GW-02 section 3). Corrected and rerun.

### Scenarios

Every byte comparison looks for the client's raw JSON text inside the journal's `body`, which is what reached the mock. `bodyTruncated` was false on every read.

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| OpenAI two-turn, both forms (28 checks) | `tool_calls`, `finish_reason: tool_calls`, members equal in the journal | `tools` (with `é`, escaped quotes, `null` in an enum, odd key order), object `tool_choice`, `parallel_tool_calls`, echoed assistant and `tool` messages byte-equal; stream reassembles to the non-stream reply; turn 2 `Sunny.`, `stop` | pass |
| Parallel calls, both forms (10) | both `tool` messages journaled, mock check accepts | turn 2 200, both byte-equal; forms agree; unknown ID 400 `param: messages[2]` | pass |
| Arguments split, `chunkChars` 3 (8) | more than one chunk per call, concatenation equals the scripted JSON | 14 argument frames per call, every fragment at most 3 characters, ID and name once per call, both equal; 33 frames from 0.012 to 0.879 s at `perTokenMs` 20 | pass |
| Multi-byte and escaped arguments, both adapters | no damage at a split | `é`, an emoji, `\"`, `\\`, `\n` reassemble to the scripted value; no U+FFFD | pass |
| Malformed `arguments`, array `tool` content, unknown members | not judged (ADR-GW-06-004) | 200, byte-equal, `x_a` to `x_d` at every level kept | pass |
| `response_format` on OpenAI, 7 forms, both forms (35 with the rows below) | forwarded unchanged | `text`, `json_object`, `json_schema` with strict true and false, a future `type` with extra members, the string `"text"`, `json_schema` with no schema: each 200 and byte-equal; `null` forwarded as `null` | pass |
| Provider 400 on OpenAI, both forms | 400, fixed message, `code` and `param` kept, one call | `invalid_json_schema`, `response_format.json_schema.schema`; provider text not in the reply | pass |
| Anthropic `text`, `null`, `{"type":"text","x":null}`, both forms (14) | ignored | 200, no `output_config`; the `text` body equals the body with the member absent | pass |
| Anthropic `json_schema`, five holders, both forms (72) | `output_config.format` verbatim, `name`, `description`, `strict` dropped | schema byte-equal; `output_config` has exactly `format`, which has exactly `type` and `schema`; dropped at any value, `7`, `[1]`, `"x"` and `null` included; `anthropic-version: 2023-06-01`, no beta header; `stream` kept; reply text in `content` and `delta.content`; with `tools` both are present | pass |
| Anthropic refusal rows: 22 bodies, both forms (176) | section 4 status, `code`, `param`, `lastSeq` unchanged | every row exact, 0 upstream calls, no value echoed; an unsafe member name gives `param` null | pass |
| Missing schema | 400, no upstream call | absent, `null`, string, `true`: `invalid_request`, `response_format.json_schema.schema`; `lastSeq` unchanged | pass |
| `json_object` on Anthropic | 400 `unsupported_value`, no upstream call | `param: response_format.type`, `lastSeq` unchanged | pass |
| Client `output_config`, 4 bodies, both forms | 400 `unsupported_parameter` | `param: output_config`, 0 upstream calls, also beside a valid `response_format` and as a number. `output_config: null` is 200 and not forwarded, as a null member counts as absent | pass |
| Anthropic provider 400 on a schema | fixed message, `upstream_rejected_request`, one call | as expected | pass |
| Estimate, both adapters and forms (50) | tool-only 7, with `abc` 8, flagged | 7 and 8; two calls 13; stream equals non-stream; header on non-stream, `llmgw.usage_estimated` on the stream; no `usage` without `include_usage`; provider usage still wins (6, no flag) | pass |
| Logs, canaries in tool name, description, arguments, result and schema text, enum values, `type` value, dropped `name`, provider message | 0 hits | 0 of 11 canaries, and 0 of `Paris`, `get_weather`, `zeta`, in the INFO log (35 lines) and the debug log (718 lines, 683 DEBUG) | pass |
| Keys in logs | 0 | `mock-local-key` 0; the one `Bearer` in 6,338 lines of B2 is the fixed 401 text, `Authorization: Bearer <key>` | pass |
| Added latency, 4,295-byte schema (section 9) | Anthropic within 1 ms of OpenAI | p50 added 1.88 ms against 1.85 ms | pass |

| 400 calls, new connection each, ms | p50 | p95 | p99 |
|---|---|---|---|
| Gateway, OpenAI | 3.00 | 22.92 | 27.62 |
| Gateway, Anthropic | 2.85 | 27.36 | 31.97 |
| Direct to the mock, OpenAI | 1.15 | 25.07 | 27.69 |
| Direct to the mock, Anthropic | 0.97 | 23.57 | 26.35 |

The p95 and p99 are as high direct as through the gateway, so they are this host's noise, not gateway overhead. Only p50 means anything here.

The mock logged 8 `MockConnectionAbort` ERROR lines, one per `disconnect` fault, as `systems/mock-provider` states.

## Findings

**1. Low, test tool, fixed: `anthropic.sh` `4b text no output_config` passed on an unmatched journal.** A lookup that finds nothing returns `{"data":[]}`, which is not empty, so `check_lacks` passes. This is `bugs/smoke-text-format-check-passes-on-an-empty-journal`, found by reading at the same time. Proof, on a scratch copy with the lookup replaced by `{"data":[]}`: the script as found printed `PASS 4b text no output_config` on both forms; the fixed one prints `FAIL 4b text reached the mock: no '"route":"anthropic.messages"' in: {"data":[]}`. That entry still says `Fix: Open`.

**2. Low, test tool, fixed: `chat-completions.sh` `4c arguments split` did not prove what section 3 asks.** It counted more than 2 frames over both calls, so one split call and one whole call passed, and it never joined the fragments. Proof, on a scratch copy with `chunkChars` 100 on the second call: as found `PASS 4c arguments split`; fixed `FAIL 4c call 1 arguments split`. It now counts per index and compares the joined text with the scripted JSON.

**3. Low, test tool, fixed: three weaker checks.**
- `"completion_tokens":7` was a fixed-string prefix, so `70` passed. All four needles now end in `,`.
- `8 no header with provider usage` and `5 429 no retry-after` expect an empty header, so a failed request passed them. Each now has a status check first.
- `anthropic.sh` 4b asserted only `strict` dropped, against section 3's three members. It now sends `description` and asserts `name` and `description` absent.

**4. Low, spec: `tools/smoke/tools-and-format.sh` does not exist.** Sections 2, 7 and 8 of the spec name it. Its scenarios are in `chat-completions.sh` and `anthropic.sh`, and the rest were run by hand here. Either the spec changes or the script is written. `ls tools/smoke/tools-and-format.sh` gives no such file.

**5. Low, gateway against the spec's words: an echoed message is equal in value, not always in bytes, and absent `content` becomes `null`.** Section 3 says the journal shows the echoed messages "equal to what the client sent". The inside of `tool_calls` stays byte-equal, member order included, but `role` and `content` are typed, so they are written first:

| Client sends | Journal body |
|---|---|
| `{"role":"tool","tool_call_id":"c","content":"x"}` | `{"role":"tool","content":"x","tool_call_id":"c"}` |
| `{"tool_calls":[...],"content":null,"role":"assistant"}` | `{"role":"assistant","content":null,"tool_calls":[...]}` |
| `{"role":"assistant","tool_calls":[...]}` | `{"role":"assistant","content":null,"tool_calls":[...]}` |

The first two are value-equal. The third is not: a member is added, as noted for GW-01 in `runs/2026-11-03-smoke-chat-completions`. The first order is a common one, so the byte checks passed only because the scripts and this driver also use `role`, `content`, `tool_call_id`. Whether a provider treats the added `null` differently is not shown: the mock accepts it, and no live check covers it.

**6. Low, spec: "the reason is in the WARN only" (section 4) is not what the WARN holds.** With a provider message of `canary-provider-reason`, the log has 0 hits. The line is `Provider call failed: deployment=anthropic status=400 code=null type=invalid_request_error providerRequestId=req_... durationMs=2`. So on Anthropic, which sends no `code` here, the reason a schema was refused reaches neither client nor operator, beyond the provider request ID. Keeping provider text out of logs is the rule `chat-completions.sh` scenario 9 checks, so the row should change, not the log.

**Observation, for the reviewer: `-0.0` loses its sign.** Beyond the exponent cases in `gotchas/json-number-literals-normalise-through-big-decimal`, `-0.0` in a schema reaches the journal as `0.0` on both adapters, and `1e-2` as `0.01`. `1.10`, `1E+3` and a 24-digit integer are unchanged. Numerically equal, so inside section 4's rule.

## What this run cannot show

- Real provider behaviour: L5 to L7 of section 8 stay open, so `output_config` on `2023-06-01`, grammar compilation against the stream deadlines, and a schema with no `additionalProperties` are unproved. The mock does not constrain output, so this proves the wire, never conformance.
- Real latency and errors. The latency here is one client on a loaded Windows host, with the mock answering at once.
- One choice per reply only: the mock refuses `n: 2`, so rounding per choice across two choices was not exercised over HTTP (`UsageEstimatorTest` covers it). Nor could a `tool_calls` member of the wrong shape be produced.
- The `openai-java` accumulator was not run here: it is in `OpenAiSdkContractTest`, part of `verify-gates`. No SDK was used by hand.
- Logs at TRACE, multi-replica effects, any load profile, the stopped mock and the OpenAI-only start.
