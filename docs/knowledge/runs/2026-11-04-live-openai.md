---
id: runs/2026-11-04-live-openai
type: run
kind: smoke
title: First live run against the real OpenAI API - TLS, completion, streams and real error bodies through the gateway
status: active
build: f227783 + uncommitted tools/smoke/live-openai.sh
area_tested: "GW-01 and GW-02 against api.openai.com, model gpt-4o-mini"
verdict: pass
tags: [live, openai, tls, streaming, errors]
related: [systems/provider-adapters, runs/2026-11-04-smoke-sse-streaming, runs/2026-11-03-smoke-chat-completions]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/live-openai.sh
---

## What was run

`bash tools/smoke/live-openai.sh`, one run, authorised by the user ("Eu autorizo a chamada live paga a openAi", 2026-11-04). The script started its own gateway-app with the key from `.env`, then a second one with a key OpenAI refuses. Billed calls: one completion and two streams, each capped at 16 completion tokens. The 404, 400 and 401 calls were refused by OpenAI.

## Results

| Check | Expected | Observed | Result |
|---|---|---|---|
| Completion over TLS | 200, `chat.completion` | as expected | pass |
| Usage | from the provider, not estimated | 24 tokens, no estimate header | pass |
| Headers | `x-request-id`, `x-gateway-deployment: openai` | as expected | pass |
| Stream with `include_usage` | `text/event-stream`, usage chunk, `[DONE]`, no `llmgw` member | as expected, 5 events | pass |
| Stream without `include_usage` | no usage chunk, `[DONE]` | as expected | pass |
| Unknown model | 404 | 404 `model_not_found` | pass |
| `temperature: 99` | 400 with OpenAI's `code` and `param`, fixed message | 400 `decimal_above_max_value`, `param` `temperature`, no provider text | pass |
| Refused key | 502 `upstream_auth_failed`, `x-should-retry: false` | as expected | pass |
| Log | no key, no prompt | neither found | pass |

Stream first byte 0.54 s, total 0.56 s (informational: one call, provider time included).

## Findings

None. The classification built against the mock and public references held against OpenAI's real 400, 401 and 404 bodies, and real stream framing went through the parser unchanged.

## What this run cannot show

- OpenAI's in-band stream errors, 429 (rate limit and quota) and 5xx: none can be provoked on demand.
- How promptly OpenAI closes after `[DONE]`, and connection reuse against its keep-alive: not measured.
- TLS failure paths: only a good handshake ran. Other models, long or tool-calling streams, and load: one short prompt on one model.
- The official SDKs against the gateway on a live call: `openai-java` is proven against the mock only.
