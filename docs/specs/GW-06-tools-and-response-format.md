---
id: GW-06
title: Tool calling and `response_format` pass-through
status: done   # not-started | in-progress | done | blocked | skipped
needs: [GW-03]
owner: —
updated: 2026-11-05
references:
  - https://platform.claude.com/docs/en/build-with-claude/structured-outputs
  - https://platform.claude.com/docs/en/api/versioning
  - https://developers.openai.com/api/docs/guides/structured-outputs
  - https://developers.openai.com/api/docs/guides/function-calling
  - https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
  - https://developers.openai.com/cookbook/examples/how_to_call_functions_with_chat_models
  - https://github.com/BerriAI/litellm/blob/main/litellm/llms/anthropic/chat/transformation.py
---

# GW-06: Tool calling and `response_format` pass-through

## 1. Context and goal

A product team or agent author on an OpenAI SDK sends `tools` and `response_format` and expects them to work on any model. Tool translation for Anthropic exists (GW-03), but nothing proves tools end to end on the OpenAI path, `response_format` is refused on Anthropic in every form (ADR-GW-03-005), and a tool-only reply with omitted usage estimates 0 output tokens.

This item closes the three pieces ADR-GW-03-009 left: the OpenAI proof, the `response_format` decision per adapter, and tool output in the estimator. It serves pillar 1 (drop-in) and pillar 4 (a tool reply is not free in the estimate). AGT-02 and GRD-04 wait on it.

## 2. Scope

### In
- `gateway-providers`: `AnthropicResponseFormatWriter`, and the `response_format` row of `AnthropicMemberPolicy` moving from Reject to Translated.
- `gateway-core`: tool output in `UsageEstimator` and `StreamTally`.
- `mock-provider`: an OpenAI tool-conversation check.
- Tests on the OpenAI path, scenarios in `tools/smoke/chat-completions.sh` and `tools/smoke/anthropic.sh`, a Revision of ADR-GW-03-017 and ADR-018.

### Out
- Validating tool arguments or schema-conforming output: GRD-04 (ADR-GW-06-004).
- Counting `tools` and echoed `tool_calls` as input (GW-17), and `message.refusal` text as output (section 11).
- Client `output_config`: still 400 `unsupported_parameter` (`AnthropicMemberPolicy.kt:82`).
- A paid live check: L5 to L7 in section 8 go to GW-11.

### What each later item changes

| Item | Change at this seam |
|---|---|
| GRD-04 | Validates output and repairs; must read `finish_reason` first, since `content_filter` and `length` may break a schema on either provider. |
| AGT-02 | Runs tool loops on this proof. Input estimates leave out tool definitions until GW-17. |
| CACHE-01 | Already covers `extras` (ADR-018), so `tools` and `response_format` enter the key with no change here. |

## 3. Acceptance criteria

**OpenAI path**
- [x] A two-turn tool conversation passes through on `complete` and `stream`: the reply has `tool_calls` and `finish_reason: tool_calls`, and the journal shows `tools`, `tool_choice`, `parallel_tool_calls` and the echoed assistant and `tool` messages equal to what the client sent, as section 4 defines equal for a message.
- [x] Two parallel calls: both `tool` messages reach the journal, and the mock's check accepts them.
- [x] On a stream with `chunkChars` 3, arguments arrive in more than one chunk per call, and concatenated by `index` equal the scripted JSON.
- [x] `openai-java`'s accumulator reads the same `tool_calls` as the non-stream reply, and every chunk passes `validate()`.
- [x] `response_format` `text`, `json_object` and `json_schema` each reach the journal with the same value; an injected provider 400 gives 400 with the fixed message and the provider's `code` and `param`.

**Anthropic path**
- [x] Every row of the section 4 table gives its stated result; a refusal leaves `lastSeq` unchanged.
- [x] `json_schema` reaches the journal as `output_config.format`, with the same `schema` value and no `name`, `description` or `strict`; `{"type":"text"}` puts no `output_config` in the body. Both hold on `complete` and `stream`, and `json_schema` with `tools` carries both.
- [x] Client `output_config` is still 400 `unsupported_parameter`.

**Estimator**
- [x] With `usage.omit`, `get_weather` and `{"city":"Paris"}` and no text give `completion_tokens` 7, flagged estimated, on both adapters and both forms. With text `abc` they give 8, one rounding per choice.
- [x] A `tool_calls` member of the wrong shape counts 0 and raises nothing.

**Mock**
- [x] A `tool` message answering no ID of the preceding `tool_calls` is 400 `invalid_request_error` with `param` `messages[i]`, `i` being that message's index; a conversation with no `tool` message is unaffected. Golden files are unchanged.

**Gates**
- [x] `verify-gates` green (coverage: core ≥ 80%, overall ≥ 70%)
- [x] Smoke scenarios in section 8 pass (`gateway-smoke`), report in `docs/knowledge/runs/`
- [x] Eval: n/a (no threshold, classifier or routing policy)
- [x] Living docs updated (`docs-update`), including the systems entries and the section 11 follow-ups

## 4. Contract

`POST /v1/chat/completions` keeps its shape: no new header, route or status. On the OpenAI path `tools`, `tool_choice`, `parallel_tool_calls` and `response_format` stay in `extras` and go upstream verbatim, as today (`ChatRequestReader.kt:20-21,37,81`, `OpenAiRequestWriter.kt:24,47`). A number keeps its value, not its spelling: an exponent normalises and `-0.0` becomes `0.0` ([gotcha](../knowledge/gotchas/json-number-literals-normalise-through-big-decimal.md)).

A message's `role` and `content` are typed and written first, so an echoed message is equal in value, not always in bytes; inside `tool_calls` bytes and member order are kept. A message without `content` reaches the upstream with `"content":null` (`OpenAiRequestWriter.kt:46`), an assistant message with `tool_calls` included, so there the upstream gets a member the client did not send. `ChatCompletionsEndToEndTest:82` pins it, asserting on the `tool` message of a conversation that has both; the assistant message was read in the journal ([run](../knowledge/runs/2026-11-05-smoke-tools-and-response-format.md), finding 5). Whether a real provider treats the added `null` differently is not shown.

### `response_format` on Anthropic

A nested member set to `null` counts as absent. Refusals are `UnsupportedByProvider`: no upstream call, GW-03's row and fixed message.

| Client sends | Result |
|---|---|
| Absent or `null` | Nothing written |
| `{"type":"text"}` | Ignored; no `output_config` (it is the default) |
| `{"type":"json_schema","json_schema":{"schema":S,...}}` | `"output_config":{"format":{"type":"json_schema","schema":S}}`, `S` not altered |
| `json_schema.name`, `.description`, `.strict` | Dropped, any value (ADR-GW-06-002) |
| `json_schema` absent or not an object | 400 `invalid_request`, `response_format.json_schema` |
| `json_schema.schema` absent or not an object | 400 `invalid_request`, `response_format.json_schema.schema` |
| `{"type":"json_object"}` | 400 `unsupported_value`, `response_format.type` |
| Another `type`, or `type` absent or not a string | 400 `unsupported_value`, `response_format.type` |
| `response_format` not an object | 400 `unsupported_value`, `response_format` |
| Another member of `response_format` or `json_schema`, `json_schema` beside `{"type":"text"}` included | 400 `unsupported_parameter`, its path (there `response_format.json_schema`), passed through `safeParam` |

The JSON arrives in a text block, so `AnthropicResponseReader` and the stream decoder change nothing: it is `message.content`, or `delta.content`.

### What a client should know

| Case | Client sees |
|---|---|
| A schema Anthropic refuses: open objects, recursion, `minimum`, `maximum`, `minLength`, `maxLength`; over 24 optional parameters, 16 unions or 20 strict tools; an assistant last message (prefill) | 400 with the fixed `The provider rejected the request...` message, and `upstream_rejected_request` unless the provider gave a safe `code`. One round trip; the provider's reason is not relayed or logged. |
| A schema or `response_format` OpenAI refuses | The same row. |
| Anthropic `refusal` | 200, `finish_reason: content_filter`, `content` possibly off-schema, `message.refusal` null (ADR-GW-03-010). |
| Output cut by `max_tokens` | 200, `finish_reason: length`, `content` possibly off-schema. |
| `strict: false` on Anthropic | Enforced anyway: Anthropic has no best-effort form. |

For a rejected schema, a client sees the status, the fixed message, and `code` and `param` when the provider gave safe ones. With neither, as in the mock's Anthropic 400, that is `upstream_rejected_request` and `param: null`, and nothing says which part of the schema failed. An operator sees one WARN, `Provider call failed: deployment status code type providerRequestId durationMs` (`AnthropicChatAdapter.kt:188`, `OpenAiCallLog.kt:45`): no provider message, since one is never logged, and no schema. The reason stays at the provider: the provider request ID is the only pointer to it, or the client sends the same request to the provider directly.

## 5. Design

### Modules and ports

No port changes. `ChatModelPort`, `ChatCompletion` and `ChatChunk` stay as they are.

- **`gateway-core`:** one pure function, used by `estimate(request, completion)` and `StreamTally.chunk`. `estimate(request, outputCharacters)` keeps its signature.
  ```kotlin
  /** Characters of `function.name` and `function.arguments` in a `tool_calls` member; other shapes count 0. */
  internal fun toolCallCharacters(extras: JsonValue.Object): Long
  ```
  A reply reads `AssistantMessage.extras`, a stream `ChunkDelta.extras`, and each adds to that choice's count before rounding. Fragments sum to the whole, so both forms agree.
- **`gateway-providers`, `.anthropic`:** `AnthropicResponseFormatWriter(nodes, refusals).write(extras, body)`, called from `AnthropicRequestWriter.body` beside `AnthropicToolWriter`, as a writer owns a Translated member's value. `.openai` does not change.
- **`mock-provider`:** `OpenAiRequests.checkToolMessages(messages)` (ADR-GW-06-005). The renderers do not change.

### Data

n/a: no table, key or topic. The gateway keeps no state for tools or schemas.

### Flow

Unchanged: [sequence-chat-completion](../diagrams/sequence-chat-completion.md). `output_config` is one more member of the written body.

### Failure model

| Situation | Behaviour |
|---|---|
| Refusal in section 4 | Before I/O: a status on both forms; RTE-02 may try another deployment, as nothing was sent. |
| Provider 400 | Relayed before commit; no retry. Malformed tool arguments or schema output in a 200 are the client's (ADR-GW-06-004). |
| Provider slow, down, disconnect | Unchanged (`provider-adapters`). Redis, Postgres, broker: not on this path beyond auth. |

Grammar compilation has a 180 s timeout, and the page does not place it relative to the first byte (L6). `stream.first-byte-timeout` is 120 s and `idle-timeout` 60 s, and any upstream byte resets the deadline (`SseStreamCall.kt:83`). So until L6 is settled, a stream with a schema new to Anthropic can see:

- Compile before the first byte, over 120 s: 504 `upstream_timeout`.
- Compile after `message_start` with no `ping`, over 60 s: 504 `upstream_timeout`.

Both come before commit, since no chunk precedes output (ADR-GW-03-007), so they are statuses, not frames. Whether a cut compile is kept for the retry, or billed, is unknown. Non-stream is safe: `request-timeout` is 300 s. An operator seeing this raises the Anthropic `stream.*` values; no default changes without evidence. OpenAI also documents first-request latency for a schema, with no figure; the same deadlines apply.

Until L5 is settled, `output_config` on the default `anthropic-version: 2023-06-01` is unproven: the versions page allows new optional inputs on a version, but the structured-outputs page shows only `2026-10-21`. If `2023-06-01` refuses it, every `json_schema` call on Anthropic is the provider-400 row. The way out is `version: 2026-10-21`, and then every forwarded `temperature`, `top_p` or `top_k` is a 400 (ADR-GW-03-002).

### Observability

No new span or metric (OBS-*). Schemas, tool definitions and arguments are never logged: they stay in `extras`, whose `toString` prints sizes, and `StreamTally` keeps counts only. `NoLeakIntegrationTest` gains a schema canary. Anthropic caches a schema for up to 24 h apart from prompts, so a schema with personal data leaves ordinary retention; `systems/provider-adapters` states it.

### Configuration

None.

## 6. Reuse

- [member-policy-table-for-a-foreign-provider](../knowledge/patterns/member-policy-table-for-a-foreign-provider.md): nested members get rules, and a rule states what the API can express, not what a model accepts. No new pattern.
- [typed-core-with-json-extras](../knowledge/patterns/typed-core-with-json-extras.md), revised here.
- ADR-GW-03-002, -005, -009, -010, -016, -017, -019; `AnthropicToolWriter` and `Refusals` as the writer's shape; `AnthropicSdkContractTest:68` as the SDK test's model; `MockBackedTest`.

## 7. Execution plan

- [x] **Step 1:** Core, test first: `toolCallCharacters`, both paths, `UsageEstimatorTest`, `StreamTallyTest`.
- [x] **Step 2:** Mock: `checkToolMessages`; `OpenAiRequestsTest:29` adds `"tool_call_id":"c"`, which the check would refuse.
- [x] **Step 3:** Anthropic: policy row, writer, and the tests that change: `AnthropicMemberPolicyTest:102` loses `response_format` (`:153` is the coverage list and stays); `AnthropicChatEndToEndTest:52` and `tools/smoke/anthropic.sh:159` become `json_object`, `unsupported_value`, `response_format.type`.
- [x] **Step 4:** OpenAI proof: conversation, parallel, stream, SDK, journal assertions.
- [x] **Step 5:** Scenarios in `chat-completions.sh` and `anthropic.sh`, smoke, `docs-update`.

## 8. Tests

- **Core unit (TDD):** `UsageEstimatorTest` (text plus tools, tools only, two calls, two choices, wrong shapes, `arguments` not a string); `StreamTallyTest` (fragments equal the whole; provider usage still wins); `ChatCompletionServiceTest` (flag set).
- **`gateway-providers` unit:** `AnthropicResponseFormatWriterTest`, a case per section 4 row, member order kept; `AnthropicRequestWriterTest` (with `tools`, on `writeStream`); `AnthropicMemberPolicyTest`.
- **`mock-provider` unit:** `OpenAiRequestsTest`, both ways: an unknown ID, no `tool_call_id`, a `tool` message with no `tool_calls` before it, a run answering parallel calls, no tools at all.
- **Integration (Testcontainers, mock provider):** `gateway-app`: new `OpenAiToolConversationIntegrationTest`; `OpenAiSdkContractTest` gains the accumulator case; new `AnthropicResponseFormatIntegrationTest`; `NoLeakIntegrationTest` extended. The mock does not constrain output, so these prove the wire, never conformance.
- **Smoke scenarios (`gateway-smoke`, `tools/smoke/chat-completions.sh` and `tools/smoke/anthropic.sh`, `local` profile).** Each script scenario is named in brackets. The full refusal table, byte comparisons, log canaries and latency ran by hand ([run](../knowledge/runs/2026-11-05-smoke-tools-and-response-format.md)).
  1. OpenAI two-turn tools, both forms; journal body read (`chat-completions.sh` 4b).
  2. Parallel calls, arguments split across chunks (`chat-completions.sh` 4c).
  3. `response_format` forms on OpenAI, journal body read (`chat-completions.sh` 4d).
  4. `json_schema` and `text` on Anthropic; each refusal row with `lastSeq` unchanged (`anthropic.sh` 4, 4b).
  5. `usage.omit` on a tool-only reply: `completion_tokens` and the flag (`chat-completions.sh` 8, `anthropic.sh` 4c).
  6. Regression: `chat-completions.sh`, `streaming.sh`, `anthropic.sh`.
- **Eval (`ai-eval`):** n/a: no threshold, classifier or routing policy.

**Paid live check: out of this run.** Numbered after GW-03's L1 to L4:

| # | Unproven against the real API | What it decides |
|---|---|---|
| L5 | `output_config` on `anthropic-version: 2023-06-01`: accepted, refused, or ignored. Check the output honours the schema, not only the status: ignored would be a silent loss of the guarantee. | Whether the default version stays, and what ADR-GW-03-002 must trade |
| L6 | Whether grammar compilation precedes the first byte, and how long a cold schema takes on a stream | Whether Anthropic's `first-byte-timeout` (120 s) and `idle-timeout` (60 s) need raising |
| L7 | A schema object with `additionalProperties` absent, not `false`: the page does not say. Non-strict OpenAI schemas often omit it. | Whether such clients get a 400, stated in the client notes |

## 9. Success metrics

- Added latency on Anthropic: within GW-03's 1 ms of the OpenAI path with a 4 KB schema.
- 0 estimated tool-only replies with `completion_tokens` 0. Estimates equal on `complete` and `stream` for every scenario.
- 0 refusals that reach the mock. 0 schema canaries in logs.

## 10. ADRs (local)

### ADR-GW-06-001: OpenAI `response_format` stays pass-through
- **Context:** ADR-004 promises the shape; the member already flows through `extras`. Support differs by model: `json_schema` needs newer snapshots (OpenAI guide).
- **Options considered:**
  - A) Validate the form in the gateway. Rejected: restates OpenAI's rules and lags them.
  - B) A per-model support table. Rejected: the table ADR-GW-03-002 refuses.
  - C) Forward every form, and relay a provider 400.
- **Decision:** C. No production change on the OpenAI path.
- **Consequences:** A new form works with no code. A bad schema costs one round trip. Buys: fewer moving parts, the provider as authority on its models.
- **Date:** 2026-11-05

### ADR-GW-06-002: `response_format` on Anthropic
- **Context:** ADR-GW-03-005 refuses it until this item. `output_config.format` takes `{type:"json_schema",schema}` with no beta header, combines with tools, and needs `additionalProperties: false`; prefill and citations are incompatible. GRD-04 will trust what a client believes it asked for.
- **Options considered:**
  - `json_object`: J1) An open schema. Rejected: impossible, as `additionalProperties` must be false. J2) A forced tool. Rejected: 400 on current top models (ADR-GW-03-016), collides with client tools, needs a model table. J3) A prompt instruction, or LiteLLM's no-schema path, which sets only a `json_mode` flag. Rejected: no guarantee, the fault ADR-GW-03-005 rejects. J4) 400 `unsupported_value`.
  - `json_schema`: S1) Filter the schema, as LiteLLM's `filter_anthropic_output_schema` moves unsupported constraints into descriptions and forces `additionalProperties: false`. Rejected: silently weakening a contract undermines GRD-04. S2) Forward verbatim, relay the 400.
  - `text`: T1) Keep the reject. Rejected: a default that changes nothing, as `n: 1`. T2) Ignore.
  - Missing schema: forward or refuse. Refuse: the gateway can already tell nothing can be honoured.
- **Decision:** J4, S2, T2, refuse. `name`, `description` and `strict` are dropped: Anthropic has no member for them.
- **Consequences:** `json_object` clients on Claude must move to `json_schema`. `description` is the one soft spot: OpenAI's reference says the model uses it, so dropping it can shift the answer, against the "Ignore cannot change the output" reading of ADR-GW-03-005. It is accepted because the schema, the contract the client relies on, is untouched. A client needing it puts it in the schema's own `description`. LiteLLM was read through a summarising fetch of the first 100,000 of 154,705 characters; its uses of `output_format` and the tool path come from that reading. Buys: no silent change of a guarantee, no model knowledge in code.
- **Date:** 2026-11-05

### ADR-GW-06-003: The estimator reads tool output from `extras`
- **Context:** `UsageEstimator.kt:30-35` and `StreamTally.kt:40-49` count `content` only, so a tool-only reply estimates 0. ADR-018's Revision says a member moves to a typed field when core must read it; ADR-GW-03-017 says "promote when core itself needs a member".
- **Options considered:**
  - A) Promote `tool_calls`. Rejected: tool types in core, three states per member, both readers and writers reworked, on the proven path, for a count used only when a provider omits usage.
  - B) Adapters report characters on the domain types. Rejected: a new field on two types, and the same logic per adapter.
  - C) Core measures the strings in `extras`, with no promotion.
  - Rounding: per call, or per choice. Per choice, with `content`, so fragments cannot round up on a stream.
- **Decision:** C, per choice, `function.name` and `arguments` only. Recorded as a Revision of ADR-GW-03-017, of [ADR-018](../DECISIONS.md#adr-018) and of the pattern: core may measure a member in `extras` without promoting it if it never branches on the value.
- **Consequences:** Readers and writers do not change. An estimate stays a lower bound: tool definitions and echoed calls are not counted on input. Buys: a small blast radius, and core staying domain-only.
- **Date:** 2026-11-05

### ADR-GW-06-004: The gateway never validates arguments or conforming output
- **Context:** A model can return malformed `arguments`, and `refusal` or `max_tokens` can break a schema on a 200.
- **Options considered:**
  - A) Check here, and return an error. Rejected: buffers a stream that must go chunk by chunk, turns a paid 200 into a failure, and duplicates GRD-04.
  - B) Pass through; GRD-04 owns validation and repair.
- **Decision:** B. `finish_reason` is the client's signal.
- **Consequences:** The row's "pass-through" means what it says. Until GRD-04 a client validates its own output. Buys: the streaming rule kept, one owner for validation.
- **Date:** 2026-11-05

### ADR-GW-06-005: The mock checks OpenAI tool messages and does not honour `response_format`
- **Context:** ADR-GW-03-019: a check tests every conversation any test sends, and validates only what public docs state. The mock has none on OpenAI's shape. The cookbook states one direction: "messages with role 'tool' must be a response to a preceding message with 'tool_calls'". The reference says `tool_call_id` is the "Tool call that this message is responding to". No page read states the converse, that every call needs an answer, for Chat Completions.
- **Options considered:**
  - Check: C1) Journal assertions alone. Rejected, as in ADR-GW-03-019. C2) The stated direction: each `tool` message has a `tool_call_id` found in the `tool_calls` of the nearest non-`tool` message before it. C3) C2 plus coverage of every ID, as on Anthropic. Rejected: no public source found; the parallel test asserts it from the journal.
  - `response_format`: F1) Generate schema-shaped output. Rejected: a schema generator in a fake, and a pass would prove the mock, not the gateway. F2) Journal only.
  - `refusal`: scriptable, or not. Not: no test needs it; add it when one does.
- **Decision:** C2, F2, no scriptable `refusal`.
- **Consequences:** `OpenAiRequestsTest:29` changes. A green suite says nothing about conformance. Buys: testability, with the mock still a fake.
- **Date:** 2026-11-05

### ADR-GW-06-006: What proves the OpenAI path
- **Context:** No OpenAI-path integration or SDK test touches tools. `extras` pass-through is proven in general, but a stream splits arguments, and SDKs parse `tool_calls` strictly.
- **Options considered:**
  - A) Unit tests on the writer and chunk reader, which exist. Rejected: they stop at the seam the row asks to cross.
  - B) A live OpenAI run. Rejected: costs money, cannot run in CI.
  - C) End to end on the mock for two-turn, parallel and stream, plus an SDK accumulator test, with `chunkChars` forcing split arguments and `response_format` asserted in the journal.
- **Decision:** C.
- **Consequences:** The default of 8 characters would split only long arguments, so tests set `chunkChars` 3. Buys: a real client on the real framing, with no paid call.
- **Date:** 2026-11-05

## 11. Log

### 2026-11-05: Plan
- **Who:** gateway-architect
- **Done:** This spec; a Revision of ADR-GW-03-017, of [ADR-018](../DECISIONS.md#adr-018) and of [patterns/typed-core-with-json-extras](../knowledge/patterns/typed-core-with-json-extras.md).
- **Decisions:** ADR-GW-06-001 to -006, taken by the orchestrator and recorded here. Found while recording: `description` can shift output (-002); only one direction of the OpenAI rule has a public source, so the check is narrower than Anthropic's (-005); `OpenAiRequestsTest:29` must change; `AnthropicMemberPolicyTest:153` need not; L7 is new; a compile after `message_start` meets the 60 s `idle-timeout`, not the 120 s one.
- **Checked against live sources on 2026-11-05:** both structured-outputs pages, the versions page, the OpenAI reference, the function-calling guide (it covers Responses, not Chat Completions), the cookbook. Fetches went through a summariser. LiteLLM: see ADR-GW-06-002.
- **Follow-ups for `docs-update`, not applied here:**
  - GW-11 note, add: "Also settles L5 to L7 of [GW-06](specs/GW-06-tools-and-response-format.md) section 8: `output_config` on `2023-06-01`, whether grammar compilation precedes the first byte against `first-byte-timeout` and `idle-timeout`, and a schema with `additionalProperties` absent."
  - New row, needed by AGT-02: "Count `tools` and echoed `tool_calls` in the input estimate. Follow-up of ADR-GW-06-003: an agent's tool definitions recur on every turn, so its estimate runs low. Only `message.refusal` text is left out on output."
  - GRD-04 note: read `finish_reason` before validating.
  - GW-03 section 4's `response_format` row; `systems/provider-adapters` (contract, Anthropic request, usage, schema retention); `systems/mock-provider` (the check).
- **Next:** Step 1.

### 2026-11-05: Built and closed
- **Who:** docs-update.
- **Done:** Steps 1 to 5. `toolCallCharacters` in `UsageEstimator` and `StreamTally`; `AnthropicResponseFormatWriter` and the policy row moved to Translated; `OpenAiRequests.checkToolMessages`; `OpenAiToolConversationIntegrationTest`, `AnthropicResponseFormatIntegrationTest`, the accumulator case in `OpenAiSdkContractTest`, the canaries in `NoLeakIntegrationTest`.
- **Smoke:** [run](../knowledge/runs/2026-11-05-smoke-tools-and-response-format.md), pass-with-findings: 1,077 checks passed and 0 failed on the final form of each, with no gateway defect. Findings 1 to 3 were script checks, fixed in `chat-completions.sh` and `anthropic.sh`, and the fixed scripts were rerun. Findings 4 to 6 and the run's observation are corrected below.
- **Corrections to this spec:**
  - Sections 2, 7 and 8 named `tools/smoke/tools-and-format.sh`, which does not exist. The scenarios live in `chat-completions.sh` and `anthropic.sh`; section 8 says which, and what ran by hand.
  - Section 4: "the reason is in the WARN only" was wrong. The WARN logs status, code and type (`AnthropicChatAdapter.kt:188`), because a provider message is never logged. Section 4 now states what a client and an operator see.
  - Sections 3 and 4: "equal to what the client sent" holds in value, not always in bytes, and a message without `content` reaches the upstream with `"content":null`, a member the client did not send.
  - Section 4: `-0.0` becomes `0.0`, with exponent normalisation: value kept, spelling not ([gotcha](../knowledge/gotchas/json-number-literals-normalise-through-big-decimal.md)).
  - Section 4: `{"type":"text"}` with a `json_schema` member is `unsupported_parameter`, read as "another member" (`AnthropicResponseFormatWriterTest`).
  - Section 3: the mock names the message through `param`, `messages[i]`.
- **Gates:** `test`, `integrationTest`, `koverVerify`, `ktlintCheck detektMain detektTest detektIntegrationTest` and `build` each gave `BUILD SUCCESSFUL`, with no Kotlin or Gradle file newer than the run. Test XML: 2,636 tests, 0 failures, 0 errors. `koverVerify` enforces the thresholds; `koverLog` was not run, so no percentage is recorded. Rule scans, rerun at closing: `TeamStoreWarmup`'s documented `runBlocking`, and two `log.debug` lines in files GW-06 did not change. 12 of 12 deliberate breaks of the OpenAI path, estimator, mock and policy turned tests red. The two scripts changed after the gates and are no Gradle input.
- **Section 11 follow-ups:** GW-11 Notes gained L5 to L7. New row GW-17, whose Notes name AGT-02. GW-03 section 4 revised. `systems/provider-adapters` and `systems/mock-provider` updated. GRD-04's row is unchanged: its Notes are empty, so nothing there is wrong, and the `finish_reason` rule stays in section 2 of this spec. AGT-02's Notes are still right, so that row is unchanged too.
- **Diagrams:** none changed: `output_config` is one more member of the written body, and no flow moved (section 5).
- **Paid live check:** no section 3 criterion needs it. L5 to L7 of section 8 stay unproven (GW-11).
- **Not shown over HTTP:** rounding across two choices, as the mock refuses `n: 2`, and a `tool_calls` member of the wrong shape. `UsageEstimatorTest` and `StreamTallyTest` prove both.
