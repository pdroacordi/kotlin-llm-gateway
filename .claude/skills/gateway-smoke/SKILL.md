---
name: gateway-smoke
description: Exercise kotlin-llm-gateway end to end over HTTP against the local stack and the mock provider - auth, completions, SSE streaming, rate limits, cache hits, guardrail blocks, fallback, traces, ledger rows, agent runs - and write a run report to docs/knowledge/runs/. Use after any change observable over HTTP, when the user says "smoke", "testa de ponta a ponta", "sobe e testa", and for a k6 load run ("load test", "teste de carga").
---

# gateway-smoke

Tests prove the code is correct in isolation; a smoke run proves the deployed pieces agree (headers, SSE framing, Redis keys, spans, ledger rows). Usually run by the `gateway-tester` agent. Until FND-02 (stack) lands, say the run is not possible and why.

## Ground rules

- **Never a real provider.** Route every model to `mock-provider` (`:8090`). A live check needs the user's go-ahead (it costs money) and is a separate, labelled run.
- **You own the apps you start, not the infra.** Start `gateway-app` / `agent-app` in the background, wait for health, run, then stop them. Leave the compose stack as you found it; never `down -v`.
- **Serial only.** Another smoke, eval or migration on the same stack corrupts both runs.
- **Evidence, not impressions.** Every finding carries the request, the status, the relevant headers or body excerpt, and the Redis key, span or row that proves it.
- Logs and raw outputs go to the scratchpad; only the report goes in the repo.

## Steps

1. **Stack up:** `docker compose -f deploy/compose.yaml ps`; start what is missing with `up -d` and note that you started it.
2. **Apps:** `./gradlew :gateway-app:bootRun` (and `:agent-app:bootRun` when agents are in scope) in the background with the `local` profile; poll `GET /actuator/health` until `UP`, at most 90 s. A start failure is the finding: quote the log. Start `mock-provider` too if the stack does not run it (`./gradlew :mock-provider:bootRun` in the background, health on `:8090`), with the latency the run needs (`MOCK_DEFAULTS_LATENCY_TTFT`, `MOCK_DEFAULTS_LATENCY_PER_TOKEN`), and `POST :8090/__mock/reset` before the scenarios.
3. **Seed:** create the teams, keys, routes, prices and policies the scenarios need through the admin API (never by SQL, so the API itself is exercised). Scenario scripts live in `tools/smoke/`.
4. **Scenarios:** run the ones the item touches, plus the baseline (auth, one completion, one stream):
   - Auth: missing or bad key gives 401 with the OpenAI error envelope.
   - Errors: an unknown route gives 404 with the OpenAI envelope and `x-request-id`; `x-client-request-id` is echoed.
   - Completion: 200, body matches the OpenAI schema, `usage` present, response headers carry request ID and cost.
   - Stream: `curl -N` shows `data:` chunks then `data: [DONE]`; first byte before the mock's full latency (no buffering); with `perTokenMs` set, chunks arrive spread over the stream, not at once.
   - Rate limit: a burst over the team limit gives 429 with `retry-after` and the limit headers; the other team is unaffected.
   - Cache: the second identical request is a hit (header), with no call reaching the mock (`/__mock/stats` `lastSeq` unchanged); a different team misses.
   - Guardrail: a prompt with a CPF is redacted before the mock sees it (`/__mock/requests?since=<seq>` body); a blocked prompt gives the documented error and no provider call.
   - Fallback: the mock set to fail the primary model (`PUT /__mock/scenarios/<model>` with an error step); the request succeeds on the fallback, and the span shows both attempts.
   - Trace: Jaeger API (`:16686/api/traces?service=gateway-app`) has the request's trace with the expected spans and GenAI attributes, and no prompt text.
   - Cost: the ledger row for the request ID exists once, with the catalog price times usage.
   - Agents: register an agent, start a run, kill the app mid-run, restart, and the run resumes without repeating a completed step.
5. **Load** (only when asked, or for OPS items): k6 from its container against the gateway with the mock at a fixed latency and `MOCK_JOURNAL_CAPTURE_BODIES=false`; record p50/p95/p99 gateway overhead, error rate and throughput. Scripts in `tools/load/`.
6. **Stop** the apps you started; confirm the ports are free.
7. **Report:** `docs/knowledge/runs/YYYY-MM-DD-<slug>.md` from `runs/_template.md`, indexed in `docs/knowledge/INDEX.md`. State what a smoke run cannot show (behaviour under real provider latency, multi-replica effects when only one replica ran).

A failed scenario goes back to the implementer with the evidence. Do not fix source code inside a smoke run.
