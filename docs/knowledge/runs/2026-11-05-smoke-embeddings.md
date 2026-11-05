---
id: runs/2026-11-05-smoke-embeddings
type: run
kind: smoke
title: POST /v1/embeddings end to end against mock-provider and compose Postgres - four input forms, both formats, status table, in-flight budget, disconnects, log canaries, openai-python; first real run of embeddings.sh
status: active
build: 1cfc745 + uncommitted GW-05, rerun on the final uncommitted tree, rechecked after the R1 and R2 fixes
area_tested: "GW-05 route, adapter, reply scan, CPU lane, in-flight budget, sliced write; tools/smoke/embeddings.sh first real run; chat-completions.sh, streaming.sh, anthropic.sh with its two new checks; rerun: write pacer, body reservation, budget default, bounded lane, refusal log, check_same; recheck: R1 lane refusals, R2 pool pending limit"
verdict: pass-with-findings
tags: [smoke, gw-05, embeddings, budget, base64, disconnect, canary, openai-python, regression, backpressure, cpu-lane]
related: [systems/provider-adapters, systems/mock-provider, patterns/opaque-json-value-as-byte-range, gotchas/netty-copies-a-heap-buffer-to-direct-on-write, runs/2026-11-04-smoke-teams-and-keys, runs/2026-11-04-smoke-anthropic-adapter]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - tools/smoke/embeddings.sh
  - tools/smoke/anthropic.sh
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingAdmission.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingResponseWriter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiEmbeddingAdapter.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/embedding/EmbeddingReplyBudget.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/ReplyWritePacer.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/upstream/CpuLane.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/upstream/TransportFailures.kt
---

## Verdict

The latest result is in [Recheck of R1 and R2](#recheck-of-r1-and-r2-2026-11-05): `pass-with-findings`, R1 and R2 closed, scripts 303 passed, 1 skipped, 0 failed, one observation (R4). The earlier final tree's result is in [Rerun on the final tree](#rerun-on-the-final-tree-2026-11-05): `pass-with-findings`, 653 passed, 2 failed, 5 skipped. Up to the Revision, this report describes the earlier build.

`pass-with-findings`. 648 checks passed and 1 failed, 2 skipped, on the final form of each check. The one failure is a defect in `anthropic.sh`, not in the gateway (finding 1). The gateway showed no defect: no vector altered, no reservation leaked in 150 random disconnects, and no input or vector in any log at INFO, DEBUG or TRACE. What stays unproved is the memory arithmetic at the worst float reply, because the mock cannot produce one (finding 3).

## What was run

- **Stack:** only `postgres` from compose (`postgres:18.6-trixie`), started by this run and stopped after it; `ps -a` showed it exited before the run. `stack-check.sh` was not run: GW-05 uses no Redis, broker or span.
- **Runtime:** Windows 11, 20 logical CPUs, 31.8 GiB, Java 25.0.4.1, gateway at `-Xmx2g`. `bootJar` rebuilt from the tree at the start of the run, run with `java -jar`.
- **mock-provider:** defaults, reset by each script and scenario. Every route pointed at it. A paid call was not made: `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` were removed from every start, root `.env` was not read, and `api.openai.com` or `api.anthropic.com` appears 0 times in all 14 log files.
- **Tools:** the four scripts; a stdlib Python driver over `http.client` and raw sockets; `openai` 3.46.0 in a scratch virtualenv. Keys stayed in process memory; 0 keys and 0 `Bearer` values in any log.
- **Beside this run:** four `candidatus-*` containers, untouched. Treat latency as noisy.

| Start | Settings | Used for |
|---|---|---|
| 1 | `local`, INFO | `embeddings.sh` unmodified, then fixed |
| A | + metrics exposed, NMT | 2048 by 3072, 10 MB requests, 8 at once, latency |
| C | `max-in-flight-bytes` and `max-response-size` 40MB, client write 5 s | the budget, disconnects, SDK, saturation |
| D | `max-response-size` 1MB | both sides of the reply limit |
| E | `openai.enabled=false`, `default-provider=anthropic` | the route with no adapter |
| bad | `max-in-flight-bytes` 40MB, `max-response-size` 256MB | refused start |
| log | INFO, `LOGGING_LEVEL_WEB=debug`, `=TRACE` | canaries |
| B | `web` debug, 2 s deadlines, client write 1 s | all four scripts with `HANG=1` and `GATEWAY_LOG` |

## Results

| Script | PASS | SKIP | FAIL | Exit | Last run |
|---|---|---|---|---|---|
| `embeddings.sh` as found, default start | 67 | 2 | 1 | 1 | never run |
| `HANG=1 embeddings.sh` fixed, `GATEWAY_LOG`, debug | 243 | 0 | 0 | 0 | first run |
| `HANG=1 chat-completions.sh`, `GATEWAY_LOG` | 60 | 1 (7, by hand below) | 0 | 0 | 60 |
| `HANG=1 streaming.sh`, `GATEWAY_LOG` | 68 | 0 | 0 | 0 | 68 |
| `HANG=1 anthropic.sh` as committed | 189 | 1 (9) | 1 (finding 1) | 1 | 184 |
| Same, `upstream_body` corrected on a scratch copy | 190 | 1 (9) | 0 | 0 | |

The totals count the second, third, fourth and fifth rows, plus 88 hand checks. Three hand checks failed first and were errors of the check, each corrected and rerun: a "dense" request of 10.8 MB got its right 413; a small call on the held model waited out the hold, so the budget was free when the next check ran; a bound compared embeddings to the same run's chat instead of to section 9's 0.9 ms.

### Scenarios

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| Four input forms | 200, one item per input, `model`, `usage`, `x-gateway-deployment`, `x-request-id` | all, for `"a"`, `["a","b"]`, `[1,2]`, `[[1],[2,3]]`; UUIDv7 ID | pass |
| float and base64 against the mock | byte-identical | SHA-256 of the raw `embedding` bytes equal, from the body, not a parser; base64 decodes to the float reply | pass |
| `encoding_format` absent | absent upstream | journal body has no `encoding_format`; reply is floats | pass |
| Forwarded members | verbatim, `llmgw` stops | `dimensions`, `encoding_format`, `user`, `x_future` in the journal; no `llmgw` | pass |
| `dimensions` | honoured | 8 values; 3072 at the batch | pass |
| `openai` 3.46.0 default | asks base64, gives floats | journal `"encoding_format":"base64"`; decoded floats equal the mock's within 1e-6; `Usage(prompt_tokens=4, total_tokens=4)` | pass |
| SDK on a 503 | retries after `retry-after-ms` | one refusal, success at 5.7 s, 2048 items | pass |
| Usage | no `completion_tokens` | none in any reply | pass |
| `usage.omit` | header and `llmgw` | `x-gateway-usage-estimated: true`, `"llmgw":{"usage_estimated":true}`, 2 tokens for `"12345"`; neither on a normal reply | pass |
| No key, wrong key, revoked key | 401 | 401, `missing_api_key`, `invalid_api_key`; `lastSeq` unchanged | pass |
| `mock-claude` | 400, `param: model`, no upstream call | 400 `unsupported_value`, `lastSeq` unchanged | pass |
| OpenAI disabled, no OpenAI key | starts, same 400 | started; `text-embedding-3-small` and `mock-claude` 400, 0 upstream calls; chat 200 | pass |
| Section 4 rows | gateway rows no call, provider rows relayed | 13 gateway rows with `param`; 7 provider rows, one call each, fixed message; 2049 items relayed with `param: input` | pass |
| 405, 415, 413 | no upstream call | `allow: POST` on GET, PUT, DELETE, PATCH; 10 MiB + 1 B is 413 | pass |
| Status table | GW-01 rows | 400, 401, 403, 404, 404 other, 408, 409, 413, 422, 429, quota, 500, 502, 503, 504: each its row, one upstream call; `retry-after: 7` kept; `code` and `param` kept on a 400 | pass |
| Faults | 502, 502, 504 | `malformed` 502, `disconnect` 502, `hang` 504 at 2,023 ms on a 2 s deadline; one journal entry each | pass |
| Closed port | 503 | 503 `provider_unavailable` in 10 ms; 200 after the mock restarted | pass |
| Client disconnect in the upstream call | cancelled | one entry, `client_cancelled` | pass |
| 2048 by 3072 base64 | 200, byte-identical | 33,657,868 B, `Content-Length`, no chunking, 428 to 730 ms (mock alone 284 to 321 ms) | pass |
| 2048 by 3072 float | 200, byte-identical | 25,268,236 B, 400 to 735 ms (finding 3) | pass |
| Densest float the mock gives, request 9.4 MB | 200, byte-identical | 35,473,619 B in 846 ms (mock alone 598 ms) | pass |
| `[[1],...]` at 10,485,732 B | relayed 400 | 400 `param: input` in 1,259 ms, one upstream call; chat p50 14.7, p95 33.7, max 68.2 ms beside it, one client process, new connection per call | pass |
| Reply limit 1 MiB | under 200, over 502 | 1,035,126 B 200 byte-identical; 1,049,306 B 502 `upstream_error`; WARN `DataBufferLimitException`; never 413 | pass |
| Budget below the reply limit | start refused | exit 1, `openAiEmbeddingAdapter` bean failed | pass |
| Added latency, small reply, 1000 interleaved keep-alive rounds | at most 0.9 ms p50 | embeddings 1.22 p50, 2.58 p95, 3.26 p99, added 0.85 ms; chat in the same run added 0.77 ms | pass |

### The in-flight budget

Start C: a full base64 batch reserves 33,554,432 B of 40 MiB, so one fits.

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| 6 calls past the budget, one held 3 s | 503 at once, no upstream call | 6 of 6 503, slowest 33 ms, `lastSeq` 6403 unchanged | pass |
| Headers and body | `retry-after`, `retry-after-ms`, no `x-should-retry` | `5`, `5000`, absent; `server_error`, `gateway_overloaded`, request ID in the message | pass |
| Small call beside the held one | fits | 200 | pass |
| Budget full, `mock-claude` | 503 before its 400 | 503 in 31 ms; 400 once free | pass |
| After completion | later call 200 | 200, 401 ms | pass |
| After upstream 500, 429, `malformed`, `disconnect` | never refused | 4 in a row each, no 503; later full call 200 each | pass |
| Disconnect during the upstream call | bytes back | `client_cancelled`, later call 200 | pass |
| Stalled write | holds its reservation | second call 503, 4 of 4 | pass |
| Reset during the write, 3 rounds | bytes back | 200 after 0.31 to 0.35 s | pass |
| Close during the write | bytes back | 200 after 0.39 s | pass |
| Client that stalls and stays connected | `client-write-timeout` frees it | 200 at 7.0 s with a 5 s timeout | pass |
| 150 disconnects at random moments, 3 at once | no leak | two full calls in a row 200; a leak of one reservation would refuse every full call | pass |
| `/v1/embeddings;a=b` | filter matches | full call 200, budget back | pass |
| Default budget, 8 full float calls with slow readers | 2 admitted, 6 refused | `[200, 200, 503 x 6]`, 2 upstream calls; budget back after | pass |

Chat while embeddings were saturated, keep-alive client in its own process, hammer of 6 threads in another (9,661 refused, 10 served in 13 s, only 200 and 503):

| | n | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| Baseline | 21,631 | 0.43 ms | 0.72 ms | 1.11 ms | 4.3 ms |
| During | 7,319 | 1.07 ms | 2.58 ms | 4.81 ms | 155.7 ms |

No chat call failed. Chat stays in single-digit milliseconds through p99, about 2.5 times its idle p50, with one outlier of 155.7 ms. The cause of the rise was not measured (finding 4).

### Log canaries

An input string in three forms, a `user`, an extra member, a token ID, a float literal, a base64 vector, and a provider `message`, sent through 200s, gateway 400s, a truncated body, a relayed 400, a 500 and a `malformed` reply.

| Level | Log lines | Occurrences of each of 7 canaries | Canaries in 8 error bodies |
|---|---|---|---|
| INFO | 40 | 0 | 0 |
| `LOGGING_LEVEL_WEB=debug` | 117 | 0 | 0 |
| `LOGGING_LEVEL_WEB=TRACE` | 377 | 0 | 0 |

Plus `embeddings.sh`'s own two canaries against the debug log of start B, a log carrying every scenario above: 0.

### Regression

`"parallel_tool_calls":"x"` gives 400 `unsupported_value`, `param: parallel_tool_calls`, passing in the script. `"llmgw":{"thinking_blocks":null}` gives a journal body equal to the same request without it, proved only on the corrected copy (finding 1).

## Findings

**1. Low, test tool: `anthropic.sh` scenario 7's body extractor matches nothing, so its "absent" check passes on two empty strings.** `tools/smoke/anthropic.sh:245` has `grep -oE '"body":"([^"\]|\.)*"'`: inside the alternative, `\.` is a literal dot, so no body with an escaped quote matches. `HANG=1 bash tools/smoke/anthropic.sh` gives `FAIL 7 null blocks reached the mock: no '\"role\":\"assistant\"' in:` and, beside it, `PASS 7 null blocks count as absent`, from `'' = ''`. Offline, on `{"seq":1,"body":"{\"model\":\"m\"}"}`, the committed pattern returns rc 1 and `'"body":"([^"\\]|\\.)*"'` returns the body. With that one change on a scratch copy the script gives 190 PASS, 0 FAIL, so the gateway is right and the script is wrong. `anthropic.sh` was left as it was: the brief gave leave to fix only `embeddings.sh`.

**2. Fixed in this run, `tools/smoke/embeddings.sh`.** As found, it reported 67 PASS, 1 FAIL, and the pass that mattered most was false.
- `python3` on this machine is the Windows stub, which exits 49. `vectors` printed nothing for both sides, so `base64 byte-identical to the mock` passed on `'' = ''`. An empty `$vector` would also have failed the canary check, since `grep -F ''` matches every line. The script now takes the first interpreter that runs and stops if there is none (control: stub interpreters gave `FAIL python`, exit 1), strips Windows' `\r`, and refuses a comparison of no vectors.
- It compared parsed values, so a changed literal would pass. It now hashes the raw bytes, and floats too.
- It asserted no item count, `x-request-id`, `model`, `usage` values, `param` on gateway rows, absence of `llmgw` and the estimate header on a normal reply, or absent `encoding_format` in the journal; and it did not exercise the status table, 415, 405, 413, 2049 items, or a revoked key. All are added.
- `[1]` for all 2048 batch items made every vector the same; ids now differ, and each batch is compared to the mock's.

**3. Not proved: the memory figures at the worst float reply.** `Embedder` adds one bucket per word or token, so a float vector is mostly `0.0`: 2048 by 3072 is 25 MB, not ADR-GW-05-006's 144 to 198 MiB, and the densest reply a 10 MB request can draw was 35 MB. So 198 MiB through the scan, the writer and the slices never ran; `EmbeddingSlicedWriteTest` and `EmbeddingBudgetTest` remain the proof there. Section 9's "peak heap per call at most 2 times the reply" is also not shown: sampled heap includes garbage (136 to 174 MiB for one call on a cold JVM, 854 MiB for 2 of 8 calls, working set 1,290 MiB on 2 GiB). Direct memory is only bounded loosely: NMT `Other` peaked at 91,407 KB across replies of 35 MB, and JMX reads 0 (finding 5). Closing this needs a provider stand-in that returns dense floats, which is a change to the mock or a raw-socket stub.

**4. Low, accepted by ADR-GW-05-011: a refusal writes an ERROR with a stack trace.** 18,090 refusals on start C came to 72,476 log lines and 6.75 MB, about 4 lines each, at up to about 740 a second, on a condition the ADR calls normal back-pressure. It may contribute to chat's rise above; that was not separated. If `gateway_overloaded` becomes frequent in practice, an ERROR per refusal will hide real errors. A question for the owner of the funnel, not a defect against the spec.

**5. For the reviewer, possible gotchas.**
- `jvm.buffer.memory.used{id=direct}` read 0 throughout 33 MB replies: Netty's pooled direct memory is not in that pool here. Smoke 5's "peak direct memory" needs NMT or Netty's own metrics.
- A client reset during a write gives one `FluxReceive` WARN, `An exception has been observed post termination`, with no request ID and no data: 20 in start C.
- Git Bash's `grep -P` refuses to run in this locale (`supports only unibyte and UTF-8 locales`).

## What this run cannot show

- Real provider latency, errors, literal widths or indentation: L1 to L3 of section 8 need the user's go-ahead. The 33-byte estimate is unchecked.
- A float reply near 198 MiB, or any reply over 35 MB through the scan (finding 3).
- `max-in-flight-bytes` at its default under memory pressure on a smaller heap: no `OutOfMemoryError` occurred, and none was sought.
- Multiple replicas: the budget is per process, and one ran.
- A parse past its deadline on the lane, and a JVM-fatal error in a scan: only unit tests reach those.
- Sustained load: the longest saturation lasted 13 s, and latency comes from one client on a shared machine.
- Chat's closed-port row was not rerun; the script's scenario 7 stays manual, and only the embeddings route was checked with the mock stopped.

Left behind: `smoke-*` teams and keys in Postgres, created through the admin API; the volume was not reset. Postgres stopped, as found; ports 8080, 8090, 5440 free.

## Revision (2026-11-05)

After this run the findings were fixed and nothing here was rerun, so this report describes the build it names, not the tree. The apps were not started. Changed since: the write path (`ReplyWritePacer`), a body reservation before the parse, so a bad body under a full budget is 503 where this run saw its 400 only with the budget free, a bounded lane, a refusal's log line (finding 4), the default budget, `anthropic.sh`'s extractor (finding 1), and `check_same` and an empty-file guard in the smoke scripts. Start C's `max-in-flight-bytes` equal to `max-response-size` is still valid. The `HANG=1 bash tools/smoke/anthropic.sh` of finding 1 is still to run.

## Rerun on the final tree (2026-11-05)

Verdict: `pass-with-findings`. 653 checks passed, 2 failed, 5 skipped: 595 in the scripts, 58 and both failures by hand. Every behaviour the fixes targeted holds: the pacer, the body reservation, the default budget, the bounded lane and the refusal log. Both failures are gateway defects in overload paths that do not behave as the spec states (R1, R2). No vector altered, no reservation leaked, 0 `OutOfMemoryError` in the gateway across 18 starts.

### What was run

- **Build:** `bootJar` up to date with the tree, 24 of 24 tasks; jars copied to the scratchpad and run with `java -jar` on Java 25.0.4.1. `git status` and the SHA-256 of `git diff` were equal before and after the runs.
- **Stack:** only `postgres`, started by this run and stopped after it, as found. `stack-check.sh` not run: GW-05 uses no Redis, broker or span.
- **mock-provider:** every route; defaults, reset per script and scenario. A paid call was not made: `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` removed from every start, and `api.openai.com`, `api.anthropic.com` and key values appear 0 times in every log.
- **Beside this run:** four `candidatus-*` containers, untouched. Treat latency as noisy.

| Start | Settings | Used for |
|---|---|---|
| B | `-Xmx2g`, `web` debug, 2 s deadlines, client write 1 s | the five scripts, `HANG=1`, `GATEWAY_LOG` |
| C | `-Xmx2g`, NMT, budget and `max-response-size` 40MB, client write 5 s | stalls, large requests, disconnects, chat under saturation, refusal log |
| M | `-Xmx4g`, NMT, budget 1GB | 24 stalled clients |
| D | `-Xmx2g`, budget unset | 8 worst float calls, body bursts |
| L, T | `-Xmx4g`, budget 1GB, `cpu-threads` 1, `cpu-queue-size` 1; T at app DEBUG | a full lane |
| E | `-Xmx2g`, all defaults, 400 small clients and 8 large parses, 4 runs | the lane and the pool at defaults (R1, R2) |
| H | `-Xmx256m`, `-Xmx512m`, budget unset | 25% of the heap |
| bad | `-Xmx1g`, budget 513MB, 600MB, 512MB | the ceiling |
| P | `max-connections` 2 | the pool's pending limit (R2) |
| tiny | budget and `max-response-size` 1MB | R3 |
| oa | `anthropic.enabled=false` | `anthropic.sh` 9, closed port |

### Scripts

| Script | PASS | SKIP | FAIL | Exit | Last run |
|---|---|---|---|---|---|
| `HANG=1 embeddings.sh` | 243 | 0 | 0 | 0 | 243 |
| `HANG=1 anthropic.sh` as it stands | 190 | 1 (9, by hand below) | 0 | 0 | 189 and 1 FAIL |
| `HANG=1 chat-completions.sh` | 60 | 1 (7, by hand below) | 0 | 0 | 60 |
| `HANG=1 streaming.sh` | 68 | 0 | 0 | 0 | 68 |
| `auth.sh`, the fifth script with `check_same` | 34 | 3 (9, 12, 13) | 0 | 0 | 40 with `PG_FAULT=1` |

`check_same` ran at all 9 call sites, 21 checks, each a PASS: 13 from the `gateway row` loop and 8 others. `anthropic.sh`'s `7 null blocks reached the mock` and `7 null blocks count as absent` both pass, so the extractor now finds the body and finding 1 above is closed.

### A client that stops reading

Full base64 batch, 33,657,868 B, `SO_RCVBUF` 64 KiB.

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| Full call | 200, byte-identical, `Content-Length` | 695 ms, equal to the mock's raw bytes | pass |
| Large, small, large, large on one connection | the pacer per reply | 4 of 4 200, 3 hashes equal | pass |
| `Connection: close` | whole reply, then EOF | 33,657,868 B, EOF | pass |
| Stalled until the write timeout cut it | little in the kernel | 393,213 B reached the client | pass |
| 24 clients stalled one by one, `-Xmx4g` | at most 2 MiB each | NMT `Other` 48,810 to 82,602 KB, 1.38 MiB a client; whole replies would be 770 MiB | pass |
| Second call beside a stall | 503 at once, no upstream call | 7 and 20 ms, `retry-after: 5`, `retry-after-ms: 5000`, no `x-should-retry`, `lastSeq` unchanged | pass |
| Small call beside it | fits | 200 | pass |
| Reset, close | budget back | full call 200 at 295 and 316 ms | pass |
| Stalled, stays connected, write timeout 5 s | freed | full call 200 at 7.9 s, the reply cut | pass |
| Pauses 3 s, or 10 s among 24, then reads | whole reply | byte-identical | pass |
| Reads at 2.8 MB/s under a 5 s write timeout | not cut | 12.1 s, byte-identical | pass |
| 150 random disconnects, 3 at once | no leak | two full calls 200, first at 0.58 s | pass |
| 23 resets at 1 GB | no leak | 29 full calls at once, 29 of 29 200 | pass |

### A large request and the budget

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| 8 bodies of 9 MB, 40 MiB budget, 3 s hold | 2 fit, 6 refused | `[200, 200, 503 x 6]`, slowest 503 63 ms, `lastSeq` +2, `retry-after: 5` | pass |
| Budget full, 9 MB unreadable body | 503, not 400 | 503 in 46 ms, no upstream call; 400 once free | pass |
| Budget full, 9 MB `mock-claude` | 503 | 503 | pass |
| Budget full, 10 MiB + 1 B | 413 first | 413 | pass |
| 6.9 MB body with a full batch's reply, alone | trimmed, runs | 200 in 499 ms | pass |
| After upstream 500, 429, `malformed`, `disconnect`, 6 large bodies each | never refused | 502, 429, 502, 502; no 503; full call 200 after each | pass |
| Budget unset, `-Xmx2g`, bursts of 32, 100, 256, 600 bodies of 9.9 MB | about 26 fit | 26, 26, 26, 29 served, `lastSeq` equal; 503 median 182 to 342 ms, slowest 935 ms | pass |
| Later call after each case | 200 | 200 | pass |

A refused body is read before it is refused, so its 503 takes as long as the upload. Heap reached 1,824 MiB of 2,048 after the 256 burst, with no `OutOfMemoryError`: ADR-GW-05-011's "bodies still being read".

### Budget default and ceiling

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| Unset, `-Xmx256m`, 67,108,864 B | bracket | priced 67,089,000: 200, 1 upstream; 67,122,000: 503, 0 upstream; WARN names 67108864 | pass |
| Unset, `-Xmx512m`, 134,217,728 B | bracket | priced 134,178,000: 200; 134,244,000: 503, 0 upstream; WARN names 134217728 | pass |
| Unset, `-Xmx2g`, 8 worst float calls, slow readers | 2 fit | `[200, 200, 503 x 6]`; later call 200 | pass |
| `-Xmx1g`, 513MB and 600MB | start refused, property named | exit 1, `llmgw.providers.openai.embeddings.max-in-flight-bytes must be at most half the heap, 536870912 bytes` | pass |
| `-Xmx1g`, 512MB | starts | started | pass |

### The CPU lane

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| `cpu-threads` 1, `cpu-queue-size` 1, 10 parses of 10.5 MB, budget 1 GB | 503 past the lane | `[400, 503 x 9]`, slowest 503 347 ms | pass |
| Envelope | as the budget's | `server_error`, `gateway_overloaded`, `retry-after: 5`, `retry-after-ms: 5000`, request ID in the message, no `x-should-retry` | pass |
| Later small and full call | 200 | 23 and 345 ms | pass |
| "No provider was touched" (section 4) | no upstream call on a 503 | 1,536 of 27,233 503s are `completed`, 200 in the journal (R1) | **fail** |

### Refusal log

| Scenario | Expected | Observed | Result |
|---|---|---|---|
| 66,042 refusals at INFO | one line a thousand | +66 lines, 15,567 B; the earlier run wrote 72,476 lines, 6.75 MB for 18,090 | pass |
| 1,392 refusals of large bodies | no flood | +1 line, 236 B | pass |
| App DEBUG, full lane | one line, no stack | 21 WARN, 20,341 DEBUG; 0 lines without a timestamp after any of them | pass |
| Pool's pending limit, a refusal of the same kind | WARN or lower | ERROR on each, up to 254 in 8 s, no frames (R2) | **fail** |

### Chat during saturation

Keep-alive client in its own process, hammer of 6 threads in another, start C. No chat call failed.

| Phase | n | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| Baseline | 13,888 | 0.54 ms | 1.78 ms | 2.60 ms | 19.6 ms |
| Full batches: 66,042 refused, 37 served | 15,035 | 0.67 ms | 1.57 ms | 4.80 ms | 26.5 ms |
| 9 MB bodies: 1,392 refused, 38 served | 3,454 | 1.46 ms | 15.79 ms | 40.32 ms | 82.1 ms |
| After | 10,534 | 0.45 ms | 0.61 ms | 0.77 ms | 6.0 ms |

Refused batches cost chat little: the worst outlier fell from the earlier run's 155.7 ms to 26.5 ms. 9 MB bodies at about 1 GB/s cost it more, p99 40 ms; the cause was not separated, and the client's own CPU is in that figure. Beside a lane of 1 and 1, with a new connection a call in the driver's process: 344 calls, all 200, p50 4.1, p99 55.4, max 222.8 ms.

### Manual rows the scripts skip

`anthropic.sh` 9: `claude-x` with Anthropic off gives 200, journal route `openai.chat`. Chat's closed port, which the earlier run left out: 503 `provider_unavailable` in 19 ms, 200 after the mock restarted; embeddings the same in 13 ms.

### Findings

**R1. Medium, gateway: a lane refusal can come after the provider answered, so a billed call is dropped and the client is told to retry.** Section 4 justifies `Overloaded` with "no provider was touched". `OpenAiEmbeddingAdapter` catches `RejectedExecutionException` around `exchange` and `interpret`, so a full lane at `readOnLane` or at the reply scan also answers `Busy`. A comment at line 76 says the provider may bill; the spec does not. Reproduction, all defaults, `-Xmx2g`, `MOCK_JOURNAL_CAPACITY=100000`: 400 keep-alive clients of `{"model":"mock-embed","input":"hello"}` beside 8 of `[[1],...]` at 10.5 MB, for 8 s. 27,233 503s, 14,227 200s, 15,767 upstream calls. Matching each client's `x-request-id` to `x-client-request-id`, 1,536 of those 503s, 9.7% of upstream calls, have a journal outcome of `completed`, status 200. The other runs gave 732 by subtraction and 909 by the same match. Each `retry-after: 5` then buys a second billed call. Either refuse only before the upstream write and let a stage past it through, or accept it and correct section 4 and ADR-GW-05-012.

**R2. Medium, gateway: the pool's pending limit is a third, undocumented refusal, reported as the provider's failure.** `UpstreamClient.kt:81` sets the pending queue to 2 x `max-connections`, so past 192 active and waiting calls at the default of 64 a call fails at once. `TransportFailures.kt:73` maps it to `provider_unavailable`. Section 5's failure table says only "`pending-acquire-timeout` 5 s, then 503". Reproduction: `LLMGW_PROVIDERS_OPENAI_EMBEDDINGS_MAX_CONNECTIONS=2`, mock `latency.ttftMs` 2000, 20 small calls at once: 6 are 200, 14 are 503 `provider_unavailable` within 98 ms, with no `retry-after`, `lastSeq` +6, and 14 ERROR and 14 WARN lines. At defaults the four runs of start E wrote 0, 56, 34 and 254 of these. The gateway's own queue is full and no provider failed, so this is what ADR-GW-05-011 rejected as option D: RTE-02 would fall back on it and send the load to a second provider, and a burst logs an ERROR per refusal. The WARN names only `WebClientRequestException`: the pool's exception is inferred from the count of exactly 3 x `max-connections` and the code at those lines, not read from a log. Chat shares the mapping, at 1,500 calls.

**R3. Low, gateway: a body over half the budget is refused forever with a `retry-after`.** `holdBody` takes 2 x the body with no trim, and the start check compares the budget only to `max-response-size`. Budget and `max-response-size` 1MB, idle gateway, body of 600,036 B: 503 `gateway_overloaded`, `retry-after: 5`, 4 of 4, no upstream call; a small call 200. A budget under 20 MiB does this to a legal 10 MB body, and an unset budget does on a heap under 80 MiB. The reply has a trim and a WARN for this case; the body has neither.

**Corrections to this run's own checks, not defects.**
- 24 calls at once first gave 8 clients no headers and one a 502: the mock, at this run's `-Xmx2g`, raised `OutOfMemoryError`. At `-Xmx10g`: clean.
- A check that opened 24 stalls at once failed at 7.36 MiB a client: NMT `Other` keeps the pool's high-water mark from 24 concurrent upstream reads, and did not fall after release. Opening them one at a time separates the two, the row above.
- `java` on the PATH is 21 and the jars need 25: the first start failed.

**For the reviewer, possible gotchas.** The first two above, and: `grep Exception` on a log matches the class `GatewayErrorWebExceptionHandler` on every refusal line.

### What this rerun cannot show

- Real provider latency, errors, billing or literal widths; L1 to L3 need the user's go-ahead. R1's cost is inferred from the mock's journal.
- A float reply near 198 MiB: the mock still tops out at 35 MB, so finding 3 stands and heap per call stays unmeasured.
- Multiple replicas: the budget, lane and pool are per process.
- Another load profile: R1 and R2 at defaults come from 400 clients on one machine, and their rates depend on the mix. The longest saturation was 13 s. No upload was slow, and a slow one holds its body outside the budget for longer.
- Whether the client or the gateway set chat's p99 under 9 MB bodies.

Left behind: `smoke-*` and `smoke-h-*` teams and keys in Postgres, created through the admin API; the volume was not reset. Postgres stopped, as found; ports 8080, 8090, 5440 free; no Java or Python process of this run left.

## Recheck of R1 and R2 (2026-11-05)

Verdict: `pass-with-findings`. R1 and R2 are closed over HTTP. 0 of 757,388 503s have a journal entry, and 42 of 42 pool refusals are `gateway_overloaded` with `Retry-After`. `HANG=1 embeddings.sh` 243 PASS and `chat-completions.sh` 60 PASS, 1 SKIP (7), 0 FAIL. One new observation, R4: under R1's load, small calls are served less often and later than in the rerun above. R3 was not retested: the fix was a sentence in section 4.

### What was run

- **Build:** `1cfc745` + uncommitted GW-05 with the R1 and R2 fixes. `bootJar` from the tree, jars copied to the scratchpad, run with `java -jar` on Java 25.0.4.1. SHA-256 of `git diff` and of `git status --short` equal before and after.
- **Stack:** only `postgres`, started by this run and stopped after it, as found. `stack-check.sh` not run: GW-05 uses no Redis, broker or span.
- **mock-provider:** every route, `-Xmx10g` with `MOCK_JOURNAL_CAPACITY=100000` for E, L and P, `-Xmx4g` at defaults for B. A paid call was not made: `OPENAI_API_KEY` and `ANTHROPIC_API_KEY` removed from every start, root `.env` not read, `api.openai.com` and `api.anthropic.com` 0 times in all 6 logs. The one `Bearer ` in the logs is the 401 message's `Bearer <key>` at web debug.
- **Beside this run:** four `candidatus-*` containers, untouched. Treat latency as noisy.

| Start | Settings | Used for |
|---|---|---|
| E | `-Xmx2g`, all defaults, INFO | R1, 4 runs, and a control with no parse clients |
| L | `-Xmx4g`, budget 1GB, `cpu-threads` 1, `cpu-queue-size` 1 | R1 with the lane at its smallest |
| P | `-Xmx2g`, `max-connections` 2, app DEBUG | R2, every refusal line visible |
| B | `-Xmx2g`, `web` debug, 2 s deadlines, client write 1 s | both scripts, `HANG=1`, `GATEWAY_LOG` |

### R1: a 503 for a call the provider completed

8 s per run, 400 keep-alive clients of `{"model":"mock-embed","input":"hello"}` in 8 processes beside 8 of `[[1],...]` at 10,485,732 B. Each client's `x-request-id` matched to the journal's `x-client-request-id`; journal `dropped` 0, `inFlight` 0 in every run. The clients ignore `retry-after`, so the 503 counts are of a closed loop, not of well-behaved clients.

| Run | Calls | 503 | 503 `completed` | 503 with no journal entry | 200, all `completed` | Relayed 400, all `rejected` | Upstream calls |
|---|---|---|---|---|---|---|---|
| E1 | 118,125 | 116,124 | 0 | 116,124 | 1,998 | 3 | 2,001 |
| E2 | 134,149 | 131,230 | 0 | 131,230 | 2,917 | 2 | 2,919 |
| E3 | 145,500 | 137,784 | 0 | 137,784 | 7,714 | 2 | 7,716 |
| E4 | 139,932 | 132,603 | 0 | 132,603 | 7,327 | 2 | 7,329 |
| S1, no parse clients | 148,050 | 116,680 | 0 | 116,680 | 31,370 | 0 | 31,370 |
| L1, lane of 1 and 1 | 124,022 | 122,967 | 0 | 122,967 | 1,053 | 2 | 1,055 |

Result: **pass**. The earlier build gave 1,536, 909 and 732 at the same load. Upstream calls equal 200s plus relayed 400s in every run, so no call reached the mock and was dropped; every 200 and 400 matched its journal entry, which shows the match works. Every 503 is `gateway_overloaded` with `retry-after: 5` and `retry-after-ms: 5000`. Log of start E: 0 ERROR, 0 `OutOfMemoryError`, 0 `provider_unavailable`; 635 refusal lines for 634,421 refusals, one in 1,000; 9 `Provider call failed` lines, all the relayed 400s. Health `UP` after the runs.

### R2: the pool's pending limit

`max-connections` 2, mock `latency.ttftMs` 2000, 20 small calls released by a barrier, 3 rounds.

| Round | 200 | 503 | Code | `retry-after`, `retry-after-ms` | `lastSeq` | Slowest 503 | Slowest 200 |
|---|---|---|---|---|---|---|---|
| 0 | 6 | 14 | `gateway_overloaded`, `server_error` | `5`, `5000` | +6 | 141 ms | 6,154 ms |
| 1 | 6 | 14 | same | same | +6 | 39 ms | 6,049 ms |
| 2 | 6 | 14 | same | same | +6 | 29 ms | 6,061 ms |

Result: **pass**. No `x-should-retry`. Later call 200 in 23 ms. A refused ID, `01a1ab7b-3b8a-7ec8-93b4-b2cd3be53c28`, has no journal entry and one log line.

| Log of start P, 42 refusals | Lines |
|---|---|
| `Request refused`, WARN | 1, the first |
| `Request refused`, DEBUG | 41 |
| ERROR | 0; the earlier build wrote 14 for 14 refusals |
| `Provider call failed` | 0; the earlier build wrote 14 WARN |
| `provider_unavailable`, in logs or replies | 0 |
| Stack frames, `Caused by` | 0; the only lines with no timestamp are the 6 of the banner |

Chat's share of R2 was not exercised: GW-16 owns it.

### Scripts

| Script | PASS | SKIP | FAIL | Exit | Last run |
|---|---|---|---|---|---|
| `HANG=1 timeout 600 bash tools/smoke/embeddings.sh`, `GATEWAY_LOG` | 243 | 0 | 0 | 0 | 243 |
| `HANG=1 timeout 600 bash tools/smoke/chat-completions.sh`, `GATEWAY_LOG` | 60 | 1 (7, manual) | 0 | 0 | 60 |

The 21 ERROR lines of start B are all rows the scripts inject: 9 `upstream_error`, 5 `upstream_timeout`, 3 `upstream_auth_failed`, 2 `upstream_quota_exhausted`, 2 `provider_unavailable`.

### Finding

**R4. Observation, for the owner of ADR-GW-05-012: under R1's load, small calls are served less often and wait behind large parses.** Later stages now queue instead of being refused, so nothing is dropped after the provider answers, at a cost visible here:

| Small calls, 8 s | 200 | p50 | p95 | p99 | max |
|---|---|---|---|---|---|
| E1 | 1,998 | 620.7 ms | 3,664 ms | 5,529 ms | 6,083 ms |
| E2 | 2,917 | 69.1 ms | 4,387 ms | 6,242 ms | 7,084 ms |
| E3 | 7,714 | 45.4 ms | 1,346 ms | 2,716 ms | 3,860 ms |
| E4 | 7,327 | 32.6 ms | 1,539 ms | 2,277 ms | 4,015 ms |
| S1, no parse clients | 31,370 | 62.3 ms | 174.8 ms | 296.4 ms | 984.9 ms |

The rerun above served 14,227 at the same load and did not record their latency, so 200s fell by 46 to 86%. With 8 parse clients, 94 to 98% of all calls are refused, and p99 is 8 to 21 times S1's. That the lane's queue is the cause is inferred from the design, not measured, and the two refusals share one code, so the split between the queue and the pool is unknown. S1 itself refuses 79%: 400 clients against 192 pool places (64 + 128) in a closed loop, which is the limit doing its work. Only 2 or 3 parses are served per run, and client CPU on the same machine is in these figures. No spec row sets a bound for this mix, so it is no failure.

### What this recheck cannot show

- Real provider latency, errors or billing: "no provider billed" rests on the mock's journal.
- Multiple replicas: the budget, lane and pool are per process.
- Clients that honour `retry-after`, any load longer than 8 s a run, or above 400 clients.
- A rejection in the adapter from a closing lane: shutdown under load was not run.
- `PoolAcquireTimeoutException`, which stays `provider_unavailable`: the 6 calls that got a place here finished inside `pending-acquire-timeout`.
- Finding 3 stands: a float reply near 198 MiB is unproved.

Left behind: `smoke-*`, `smoke-r-*` and `smoke-r2-*` teams and keys in Postgres, created through the admin API; the volume was not reset. Postgres stopped, as found; ports 8080, 8090, 5440 free.
