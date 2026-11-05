---
id: GW-05
title: POST /v1/embeddings
status: done   # not-started | in-progress | done | blocked | skipped
needs: [GW-04]
owner: —
updated: 2026-11-05
references:
  - https://developers.openai.com/api/reference/resources/embeddings/methods/create
  - https://developers.openai.com/api/docs/guides/embeddings
  - https://github.com/openai/openai-python/blob/main/src/openai/lib/_parsing/_embeddings.py
  - https://platform.claude.com/docs/en/build-with-claude/embeddings
  - https://github.com/BerriAI/litellm/blob/main/litellm/main.py
  - https://github.com/Portkey-AI/gateway/blob/main/src/providers/anthropic/index.ts
  - https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/JsonParser.java
  - https://github.com/FasterXML/jackson-core/blob/3.x/src/main/java/tools/jackson/core/json/UTF8StreamJsonParser.java
  - https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/client-embeddings.md
---

# GW-05: POST /v1/embeddings

## 1. Context and goal

A product team that points an OpenAI SDK at the gateway can call chat but not embeddings, so it still holds a provider key for its search or RAG code. After this item `POST /v1/embeddings` answers as OpenAI does, under a team key, with the provider key kept in the gateway.

It serves pillar 1 (drop-in, ADR-004 names the path) and pillar 4 (the call has a team). CACHE-02 and AGT-05 need it.

Two things differ from chat. A reply can be 200 MB of numbers the gateway has no reason to read, and Anthropic has no embedding model. Both shape the design.

## 2. Scope

### In
- `gateway-core`, package `.embedding`: `EmbeddingModelPort`, the types, `RoutingEmbeddingModelPort`, `EmbeddingService`, `EmbeddingCpuPort`, `worstCaseReplyBytes`, `EmbeddingReplyBudget`, a `UsageEstimator` overload, `InvalidRequest.inputRequired`; `GatewayError.Overloaded` and its row.
- `gateway-providers`: `OpenAiEmbeddingAdapter`, its writer, reader and usage reader; `.json` byte-range scan; `.upstream.CpuLane`.
- `gateway-app`, package `.embedding`: route, handler, request reader, response writer, `EmbeddingAdmission`, `EmbeddingReplyFilter`, `ReplyWritePacer`; `embeddings.*` properties; wiring in `ProviderConfiguration`.
- `tools/smoke/embeddings.sh`, the `provider-adapters` entry.

### Out
- Any change to `mock-provider`: it already serves all four input forms, 2048 items, `dimensions` and both formats (`OpenAiHandlers.kt:56-80`).
- Voyage AI or any other provider: new scope under ADR-004, a `[?]` follow-up (section 11).
- Caching, decoding a vector, limits, cost, spans (CACHE-02, RL-02, COST-01, OBS-01).
- Retries and fallback (RTE-02). Aliases and per-capability routes (RTE-01).
- A paid live check. Section 8 lists what only one can prove; it needs the user's go-ahead.

### What each later item changes

| Item | Change at this seam |
|---|---|
| CACHE-02 | Decodes `RawJson`: base64 is little-endian float32. Calling the port itself, it should ask for `base64`, 5.3 bytes a value against up to 33. |
| RTE-01 | Replaces the resolver. Until then embeddings need `default-provider: openai` (section 5, Selection). |
| RTE-02 | Adds retries and fallback in `EmbeddingService`. May fall back on `UnsupportedByProvider`. |
| RL-02 | Pre-counts tokens with `UsageEstimator.estimate(EmbeddingInput)`. Tokens do not bound a reply's size (ADR-GW-05-006). Owns any per-team share of the budget, which is process-wide here (ADR-GW-05-011). |
| COST-01 | `outputTokens` is 0, so input price times `inputTokens` is the whole cost. |
| GRD-02 | Scans `Text` and `Texts`. `TokenIds` hold no text it can read, so it must say what it does with them. |
| OBS-01 | `gen_ai.operation.name` is `embeddings`; section 5, Observability. |

## 3. Acceptance criteria

- [x] With a team key, each of the four `input` forms gives 200 with `object: list`, one `data` item per input, `model`, `usage`, `x-gateway-deployment: openai` and `x-request-id`.
- [x] `encoding_format: base64` returns each `embedding` string byte-identical to the mock's; a float reply keeps each number literal, including `1e-5` from a raw-socket provider and whitespace inside the array.
- [x] `encoding_format`, `dimensions`, `user` and an unknown member reach the provider verbatim, and an absent `encoding_format` stays absent (journal); `llmgw` does not reach it. An unknown member of a reply, at the top, in an item and in `usage`, reaches the client verbatim.
- [x] `openai-java` with its base URL on the gateway reads the floats the mock produced.
- [x] Each row of section 4's validation table behaves as written; gateway rows make no upstream call (`lastSeq` unchanged); a relayed 400 keeps the provider's `code` and `param` with the fixed `message`.
- [x] With Anthropic enabled, `mock-claude` gives 400 `unsupported_value` with `param: model`. With `openai.enabled=false`, `default-provider=anthropic` and no `OPENAI_API_KEY`, the app starts and the route gives that same 400.
- [x] A reply has `prompt_tokens` and `total_tokens` and never `completion_tokens`, also when the provider sends one (`OpenAiEmbeddingUsageReaderTest`).
- [x] A reply with no `usage` gives 200 with estimated counts, `x-gateway-usage-estimated: true` and `llmgw.usage_estimated: true`; a reply with `usage` has neither.
- [x] 2048 inputs at 3072 dimensions in base64 (32 MiB) give 200 at the default limit. At a lowered limit the same reply gives 502 `upstream_error`, not 413.
- [x] `OpenAiEmbeddingReaderTest` proves the reader allocates under 3 times the body's size on a body of 1,000,000 floats (`getThreadAllocatedBytes`), so a tree read cannot return unnoticed.
- [x] The reply scan, and the parse, typing and upstream write of a request, run on `embeddings-openai-cpu-*`, never on `reactor-http-*` or `parallel-*`; the handler's write follows the scan there (`EmbeddingThreadTest`, with a large `[[1],...]` request).
- [x] With the budget full a call gets 503 `gateway_overloaded` with `retry-after` and `retry-after-ms`, and no upstream call. A reservation is held through the response write and released on completion, a provider failure, a bug, a disconnect during the upstream call and a disconnect during the write: `EmbeddingBudgetTest` exhausts the budget, ends the calls, and succeeds again.
- [x] A full lane refuses only a request's parse: a reply or an error body the provider sent is served, whatever the queue holds (`OpenAiEmbeddingAdapterFailureTest`, `CpuLaneTest`).
- [x] Past the pool's pending limit a call gets 503 `gateway_overloaded` with `retry-after`, nothing goes upstream and no provider failure is logged (`OpenAiEmbeddingAdapterFailureTest`, `OpenAiEmbeddingAdapterLogTest`); the exception is `PoolAcquirePendingLimitException` (`UpstreamClientPoolTest`).
- [x] A JVM-fatal error in the scan ends the call with `UpstreamTimeout` at the deadline (`OpenAiEmbeddingAdapterFailureTest`).
- [x] A reply reaches the socket as writes of at most about 1 MiB, with `Content-Length` (`EmbeddingSlicedWriteTest`).
- [x] A non-2xx body is read once, on the lane; over 64 KiB it is not read, and the failure is the same with no fields.
- [x] `hang` gives 504 within `embeddings.request-timeout`; a closed port gives 503; a client disconnect cancels the upstream call; one failed call is one journal entry.
- [x] An input canary and a vector literal appear in no log line with `logging.level.web=TRACE`, and in no error body. `toString` of every input-bearing type prints sizes only.
- [x] `ProviderLifecycleTest` counts one close each for `openai`, `anthropic` and `embeddings-openai`, with its two existing assertions unchanged.
- [x] Concurrent first requests pass under BlockHound.
- [x] `verify-gates` green (coverage: core ≥ 80%, overall ≥ 70%)
- [x] Smoke scenarios in section 8 pass (`gateway-smoke`), report in `docs/knowledge/runs/`
- [x] Eval: n/a (section 8)
- [x] Living docs updated (`docs-update`), including the systems entry

## 4. Contract

`POST /v1/embeddings`, OpenAI-compatible (ADR-004). `Authorization: Bearer llmgw_...` as on chat (`AuthWebFilter` covers `/v1/**`; `AuthScopeTest` sees the route). `Content-Type: application/json` is required (415), the body limit is the 10 MB of `readJsonObject` (413), `Accept` is ignored, another method is 405 with `allow: POST`.

```json
{"model":"text-embedding-3-small","input":["a","b"],"dimensions":256,"encoding_format":"base64"}
```

```json
{"object":"list",
 "data":[{"object":"embedding","index":0,"embedding":"<the provider's bytes>"}],
 "model":"text-embedding-3-small",
 "usage":{"prompt_tokens":2,"total_tokens":2}}
```

`embedding` is the provider's value byte for byte: an array of numbers, or a base64 string. The writer puts typed members first and extras after them, so member order can differ from the provider's; JSON does not depend on it.

**Headers.** `x-gateway-deployment`, `x-request-id`, and `x-gateway-usage-estimated: true` on an estimate.

**Extension.** On an estimate the body also has top-level `"llmgw":{"usage_estimated":true}`, the member a chat stream uses (ADR-GW-05-007). A body with provider usage has no `llmgw`.

### Validation: who answers

The gateway refuses only what it cannot type. Everything else goes upstream, so OpenAI's rule stays OpenAI's and a changed limit needs no release (ADR-GW-05-009). Limits from the reference: 1 to 2048 items, 8192 tokens per input, 300,000 tokens per request, no empty string.

| Request | Who | Result |
|---|---|---|
| `model` absent, blank or not a string | gateway | 400 `invalid_request`, `param: model` (`modelRequired`) |
| `input` absent or `null` | gateway | 400 `invalid_request`, `param: input` (`inputRequired`) |
| `input` a number, boolean or object | gateway | same |
| Array mixing types, a non-integer token, or an integer past `Long` | gateway | same |
| Model mapped to a provider with no embeddings | gateway | 400 `unsupported_value`, `param: model`, no upstream call |
| `input: ""`, `[]`, `[""]`, `[[]]` | provider | relayed 400 |
| More than 2048 items | provider | relayed 400; the body limit bounds the read |
| Over 8192 or 300,000 tokens | provider | relayed 400; the gateway has no tokenizer |
| `dimensions` of any bad value, or on a model without it | provider | relayed 400 |
| `encoding_format` of any bad value | provider | relayed 400 |
| `user` of a bad type, unknown members | provider | as the provider answers |

The gateway's checks run in the table's order and the first failure answers. A relayed 400 is `RejectedByProvider`: the provider's `code` and `param` when they pass the regex, and fixed `message` text (ADR-GW-01-005). Other upstream failures are GW-01's rows, unchanged.

### Overload

| Condition | Status | `type` | `code` | Headers |
|---|---|---|---|---|
| The budget cannot hold the call's body or its worst-case reply, the lane's queue is full at the request's parse, the lane is closed, the pool's pending queue is full, or the request's parse passed its deadline on the lane | 503 | `server_error` | `gateway_overloaded` | `retry-after`, `retry-after-ms` from `embeddings.busy-retry-after` |

`GatewayError.Overloaded`, not `ProviderFailure`: no provider failed, and RTE-02 must not read this as one that did (ADR-GW-05-011). Every row is refused before anything goes upstream, with one exception: a lane closed by shutdown refuses a stage of any call, so a call in flight at `close()` can be billed and dropped. No `x-should-retry: false`: a retry is what the header asks for.

A call reserves twice: its body before the parse, so with the budget full even an unreadable body gets this 503, and its reply after the gateway's rows and before the router, so a model on a provider with no embeddings gets this 503, not its 400. Each body-size row (413, 415) still answers first: they come from the read.

A body over half the budget is always refused, with a `retry-after` that cannot help: `holdBody` takes 2 x the body with no trim, unlike the reply. This is accepted with no code change, because it only happens at budgets far below the default: a legal 10 MB body needs a budget of 20 MiB, against 512 MiB on a 2 GiB heap, so it takes a set budget under 20 MiB, or an unset one on a heap under 80 MiB. A small call on such a gateway still runs (run report, R3).

**Narrower than OpenAI, by design:** as on chat, `message` on a relayed 4xx is fixed text, the body limit is 10 MB, and a number in an extra normalises ([gotcha](../knowledge/gotchas/json-number-literals-normalise-through-big-decimal.md)); a vector does not. `total_tokens` is written as `prompt_tokens`, a provider's `completion_tokens` is dropped (ADR-GW-05-007), and `object` is always `list` and `embedding`.

## 5. Design

### Modules and ports

`gateway-core`, package `.embedding`. It imports `Deployment`, `ModelName`, `ModelResolver`, `ProviderId`, `TokenCount`, `Usage` from `.chat`; `UsageEstimator` imports `EmbeddingInput`. That is a package cycle inside one module, which no ARCH rule forbids, and a rename of shared types is out of proportion here.

```kotlin
sealed interface EmbeddingInput {          // each overrides toString with sizes only
    class Text(val text: String) : EmbeddingInput                // "a"
    class Texts(val texts: List<String>) : EmbeddingInput        // ["a", "b"], or [], which goes upstream
    class TokenIds(val ids: LongArray) : EmbeddingInput          // [1, 2]
    class TokenIdLists(val lists: List<LongArray>) : EmbeddingInput  // [[1], [2]]
}

data class EmbeddingRequest(val model: ModelName, val input: EmbeddingInput, val extras: JsonValue.Object)
data class EmbeddingCall(
    val requestId: RequestId,
    val team: TeamId,                      // adapters ignore it
    val deployment: Deployment,
    val input: EmbeddingInput,
    val extras: JsonValue.Object,          // dimensions, encoding_format, user, unknown members
)

class RawJson(val bytes: ByteArray)        // one JSON value, UTF-8, never parsed; equal by content; toString: size
data class Embedding(val index: Int, val vector: RawJson, val extras: JsonValue.Object)
data class EmbeddingList(val model: ModelName, val data: List<Embedding>, val extras: JsonValue.Object)

sealed interface EmbeddingResult {
    data class Embedded(val list: EmbeddingList, val usage: Usage?) : EmbeddingResult  // null: provider omitted it
    data class Failed(val failure: ProviderFailure) : EmbeddingResult
}

interface EmbeddingModelPort {
    /** Never throws for an expected failure. Cancellation of the caller cancels the upstream call. */
    suspend fun embed(call: EmbeddingCall): EmbeddingResult
}

class RoutingEmbeddingModelPort(ports: Map<ProviderId, EmbeddingModelPort>) : EmbeddingModelPort
class EmbeddingService(resolver: ModelResolver, port: EmbeddingModelPort, estimator: UsageEstimator) {
    suspend fun embed(requestId: RequestId, team: TeamId, request: EmbeddingRequest): EmbeddingOutcome
}

// UsageEstimator: sum of ceil(length / 4) per text, or the id count per token array. Output 0, estimated true.
fun estimate(input: EmbeddingInput): Usage
```

`EmbeddingOutcome` is `Answered(deployment, list, usage)` or `Rejected(error)`, as `ChatOutcome`. Each type with `extras` rejects a key that is also a typed field.

- **`gateway-providers`:** `.json.JsonMembers`, the byte-range scan and the only use of Jackson's streaming API. In `.openai`: `OpenAiEmbeddingAdapter` (`UpstreamClient` named `embeddings-openai`, `warmUp`, `close`), `OpenAiEmbeddingRequestWriter` (`model` from the deployment, `input` in its form, extras less `llmgw`), `OpenAiEmbeddingReader`, `OpenAiEmbeddingUsageReader`.
- **`gateway-app`:** `.embedding` holds `EmbeddingRoutes`, `EmbeddingHandler`, `EmbeddingRequestReader`, `EmbeddingResponseWriter`, `EmbeddingAdmission`, `EmbeddingReplyFilter`, `EmbeddingConfiguration`. `.config.ProviderConfiguration` holds the adapter bean, `embeddingModelPort` and `embeddingCpuPort` (ARCH-12).
- **Small moves:** `OpenAiChatAdapter`'s two log lines to an internal `OpenAiCallLog`, same text; `toTokenCount` to internal; `USAGE_ESTIMATED_MEMBER` to `ChatExtensions`.

`OpenAiUsageReader`, `ChatResponseWriter.usage` and the chat `estimate` are not reused: the first returns null without `completion_tokens` (`:17`), so every reply would be flagged estimated; the second writes that member (`:56`); the third takes a `ChatRequest`.

**The reply scan** (ADR-GW-05-005). `JsonMembers` walks one parser over the body. For each member it takes `currentTokenLocation().getByteOffset()`, then `skipChildren()` for a container or `finishToken()` for a string, whose token is otherwise incomplete, then `currentLocation().getByteOffset()`, and copies that range. Number literals are lexed and never converted, and nothing is built per element.

| Member | Rule |
|---|---|
| Top: `data` | Non-empty array of objects, else unreadable |
| Item: `embedding` | Array or string, copied to `RawJson` unread, else unreadable |
| Item: `index` | Integer in `Int` range, else unreadable: without it a vector cannot be matched to its input, and a guess corrupts a search silently |
| Top: `model` | Non-blank string, else unreadable |
| Top: `usage` | Slice through `readTreeOrNull`, then `OpenAiEmbeddingUsageReader`; absent, `null` or malformed is omitted |
| `object` at either level | Skipped; the writer writes the constant |
| Anything else, either level | Slice through `readTreeOrNull`, then `toJsonValue`, into `extras`; a top-level `llmgw` is dropped |

Unreadable gives `UpstreamError`, as on chat. So do a trailing token after the root and a byte offset of -1, which Jackson returns for a body it reads as UTF-16 or UTF-32. The read catches `JacksonException` and `NumberFormatException` and keeps neither message: both quote the input.

**The writer.** `EmbeddingResponseWriter` fills one `ByteArrayOutputStream`, sized from the sum of the vectors plus 64 bytes an item and 1 KiB. Fixed parts are constants; `model`, `usage` and extras go through `mapper.writeValueAsBytes`; vectors are appended raw. `JsonGenerator.writeRawValue` would widen every byte to a `char`. `EmbeddingSlicedBody` sets `Content-Length` and writes `slices`, 1 MiB views of the one array, one flushed group each; on a reply of more than one slice `ReplyWritePacer` holds each slice back until the channel is writable (ADR-GW-05-013). No encoder runs, so nothing logs the body.

**The in-flight budget** (ADR-GW-05-011). Before the parse, `EmbeddingAdmission` takes twice the body's length from the one `EmbeddingReplyBudget`, or refuses. After typing it prices the reply with the pure `worstCaseReplyBytes(request, limits)` and takes that too, or refuses: a reply over the whole budget is always refused, and one that fits alone is trimmed by what its call holds, so a call at the budget can still run alone. There is no queue.

| Input | Value |
|---|---|
| Items | `Text` 1, `Texts` its size, `TokenIds` 1, `TokenIdLists` its size |
| Dimensions | `dimensions` when a positive integer, else `embeddings.default-dimensions`: a value the provider will refuse needs no precision |
| Bytes a value | `base64` 16/3, anything else 33, ADR-GW-05-006's table: `float` is the API's default, and a value the provider will refuse is priced as the widest |
| Result | items x dimensions x bytes a value, rounded up, capped at `max-response-size` |

Both reservations live in a `ReplyHold`, an exchange attribute set by `EmbeddingReplyFilter`, whose `doFinally` ends them. The handler returns before the write, so nothing inside the handler could see the write end; the filter's chain includes the write and sees completion, error and cancel alike. The hold is created before the handler runs and tolerates either order, so a cancel racing the handler cannot leak. The filter matches with the router's `PathPattern`: `/v1/embeddings;a=b` reaches the handler, and a string compare would miss it.

**The CPU lane** (ADR-GW-05-012). `CpuLane` is a `Schedulers.newParallel` of `embeddings.cpu-threads` daemon threads named `embeddings-openai-cpu-*`, with a deadline after `subscribeOn`, and a counter of stages on the lane. Admission, the request's parse, is refused once that counter passes `cpu-threads` plus `cpu-queue-size`; every later stage is counted but never refused for it, and the budget bounds them (ADR-GW-05-012). The adapter owns it and disposes it at `close()`. Six stages run there: the parse and typing of a request (the handler borrows the lane through `EmbeddingCpuPort`, since ARCH-12 keeps `.upstream` out of it), the write of the upstream body, the copy of the reply to an array, the reply scan, and the read of an error body. `awaitBody` still joins the request's buffers on the event loop: one copy of at most 10 MB. Every call pays the hops, since a threshold would be a second path. A refusal of the parse is `RejectedExecutionException` from `run`, and the handler answers 503 `gateway_overloaded`. In the adapter that exception means a closed lane, so shutdown only; it is `EmbeddingResult.Busy`, as is the pool's pending limit, `EmbeddingOutcome.Busy` from the service, and the same 503.

**Selection.** `PrefixModelResolver` is reused. `RoutingEmbeddingModelPort` answers `Failed(UnsupportedByProvider("unsupported_value", "model"))` for a provider not in its map. The resolver only yields enabled providers, so a miss always means that provider offers no embeddings, and core names no provider. `ProviderConfiguration` puts only OpenAI in the map, and an empty map is valid. Consequence: with `default-provider: anthropic` and OpenAI enabled, `text-embedding-3-small` resolves to Anthropic and gets the 400, since OpenAI has no prefixes. RTE-01 ends that; until then a deployment that wants embeddings keeps `default-provider: openai`.

### Data

n/a: no table, Redis key or topic. The gateway keeps nothing of a request or reply.

### Flow

[sequence-chat-completion](../diagrams/sequence-chat-completion.md), with three differences: the router can answer without an adapter, CPU work on a body runs on the adapter's lane, and a call holds a reservation from before the upstream call to the end of the response write.

### Failure model

Fail-closed: there is no answer to give. No retries; RTE-02 owns them. The call changes nothing at the provider, so no idempotency key is needed.

| Dependency or event | Behaviour |
|---|---|
| Provider slow | `embeddings.request-timeout` (120 s) bounds connection, send and whole-body read through `Mono.timeout`; 504 `upstream_timeout` |
| Provider down | Connect 5 s, then 503 `provider_unavailable`; nothing sent |
| Pool full | A call waits up to `pending-acquire-timeout`, 5 s, then 503 `provider_unavailable`: the provider held every connection that long. The pool bounds connections, not memory: a slot is free before the scan and the write |
| Pool's pending queue full, 3 x `max-connections` active and waiting | 503 `gateway_overloaded` with `retry-after` at once, nothing sent, logged as a budget refusal is (ADR-GW-05-011) |
| Budget full | 503 `gateway_overloaded` with `retry-after` at once, no queue, nothing upstream. This is what back-pressure on large calls looks like. A refusal logs one line, WARN for the first of each thousand and DEBUG for the rest, with no stack trace |
| Lane queue full | At the parse, 503 `gateway_overloaded`, nothing having gone upstream. Any later stage waits its turn, so a reply the provider sent is never thrown away for a full queue (ADR-GW-05-012) |
| Lane closed | Shutdown only: 503 `gateway_overloaded` at any stage. Past the upstream write the provider may bill, and the reply is thrown away |
| Reply wider than its reservation | Still served, up to `max-response-size`: the estimate takes 33 bytes a value, which L1 checks. Only that call's excess is uncounted |
| Slow client | Holds its reservation until the write ends, and at most about one slice of direct memory; a stall is cut by `client-write-timeout`. No per-team share until RL-02, so one team's slow readers can take the budget and others get 503 instead of an OOM |
| JVM-fatal error or queue wait on the lane | A fatal error sends no signal ([gotcha](../knowledge/gotchas/reactor-jvm-fatal-error-leaves-the-subscriber-unsignalled.md)), so the deadline after `subscribeOn` ends the stage: `embeddings.request-timeout`, a second one after the exchange. In the adapter 504 `upstream_timeout`; on the request's parse 503 `gateway_overloaded`, nothing having gone upstream |
| Non-2xx body over 64 KiB | Not read: the row for its status with no `code` or `param`, and `Retry-After` kept (ADR-GW-05-014) |
| Reset after the write, unreadable 2xx | 502 `upstream_error`; the provider may bill |
| Reply over `embeddings.max-response-size` | 502 `upstream_error`, never 413; billed and thrown away, with a WARN naming `DataBufferLimitException` |
| Provider usage missing or malformed | 200, estimated and flagged |
| Client disconnects | Coroutine, Mono and connection cancelled; a stage queued on the lane never starts. A stage already running finishes, under 1 s at the limit by estimate, and its result is dropped: nothing in it polls for an interrupt. The reservation is released at the cancel, so for that second the budget undercounts one scan |
| Team store (Postgres) | GW-04's behaviour, before this route is reached |
| Redis, broker | n/a: not on this path |

**Accepted risk.** A request of about 8 KB (`[[1],[1],...]`, 2048 items) at 3072 dimensions draws a reply of up to 198 MiB, and token limits do not see it (ADR-GW-05-006). `embeddings.max-in-flight-bytes` caps the sum of such replies and of request bodies, so the risk is now refused calls, not an OOM. Two things stay outside the budget: a body while it is read, before it can be reserved, bounded by the 10 MB limit and the connection count, and the tree of a running parse, bounded by `cpu-threads` (ADR-GW-05-011).

### Observability

No spans or metrics exist yet (OBS-01, OBS-02). The adapter logs the two lines chat does: DEBUG `Provider call answered` (deployment, status, provider request ID, duration, input tokens, estimated) and WARN `Provider call failed`. For OBS-01: span `embeddings {gen_ai.request.model}`, with `gen_ai.operation.name=embeddings`, `gen_ai.embeddings.dimension.count`, `gen_ai.request.encoding_formats`, `gen_ai.usage.input_tokens`.

Never recorded, at any level: `input`, token IDs, `user`, a vector, a provider `message` (ADR-GW-05-008).

### Configuration

`application.yaml`, nested in `OpenAiProperties`, checked in `toEmbeddingAdapterConfig()` only when OpenAI is enabled. With OpenAI off the route can only refuse, so the budget is built unchecked and the lane is a one-thread `embeddings-edge-cpu`, with a fixed 30 s deadline and a queue of 256.

| Property | Default | Note |
|---|---|---|
| `llmgw.providers.openai.embeddings.request-timeout` | `120s` | > 0, whole call. No public source; 198 MiB at 100 Mbit/s is 17 s, plus the provider's time on 300,000 tokens |
| `llmgw.providers.openai.embeddings.max-response-size` | `256MB` | 1 KB to 256 MB, the bound `ProviderChecks` already holds. ADR-GW-05-006 |
| `llmgw.providers.openai.embeddings.max-connections` | `64` | > 0, the cap on concurrent upstream reads. No public source; it does not cap memory, the next row does |
| `llmgw.providers.openai.embeddings.max-in-flight-bytes` | unset | Unset, 25% of `Runtime.maxMemory()`: 512 MiB on a 2 GiB heap. Set, at least `max-response-size`, so a reservation at the cap can run alone, and at most 50% of the heap, or the start fails naming it. Process-wide sum of reservations; heap is about 2 x this. Arithmetic in ADR-GW-05-011 |
| `llmgw.providers.openai.embeddings.default-dimensions` | `3072` | > 0. What a request with no `dimensions` is priced at: the largest documented output, so never short for a documented model |
| `llmgw.providers.openai.embeddings.cpu-threads` | `4` | > 0, the lane's size. ADR-GW-05-012 |
| `llmgw.providers.openai.embeddings.cpu-queue-size` | `256` | > 0, stages that may wait beyond `cpu-threads` before a parse is refused. Later stages are not limited by it. No public source. ADR-GW-05-012 |
| `llmgw.providers.openai.embeddings.busy-retry-after` | `5s` | > 0, `Retry-After` on `gateway_overloaded`. No public source; a 5 MB reply drains in 0.4 s and a 198 MiB one in 17 s at 100 Mbit/s |

`base-url`, `api-key`, `connect-timeout` and the other `pool.*` values are shared with chat. `ProviderChecks.maxResponseSize` gains the property's name, so a refusal names the right one.

## 6. Reuse

- Code: `readJsonObject`, `exactTreeReader`, `extrasExcept`, `JsonValue`, `modelRequired`, `ModelResolver`, `ProviderFailure`, `GatewayHeaders`, `OpenAiFailureClassifier`, `TransportFailures`, `UpstreamClient.exchange`.
- Patterns: `upstream-bytes-then-pure-classification`, `typed-core-with-json-extras`, `one-error-funnel`, and the new [opaque-json-value-as-byte-range](../knowledge/patterns/opaque-json-value-as-byte-range.md). `member-policy-table-for-a-foreign-provider` does not apply: Anthropic has no endpoint to translate to, so no member needs a rule. Its pitfall "do not reuse a provider-rejected failure" does, which is why the 400 is `UnsupportedByProvider`.
- ADRs: ADR-004, ADR-018 (revised here), ADR-020, ADR-GW-01-004, -005, -011, ADR-GW-03-017, ADR-GW-04-009.

## 7. Execution plan

- [x] **Step 1, core:** types, port, router, service, estimator overload, `inputRequired`; tests first.
- [x] **Step 2, edge:** `EmbeddingRequestReader`, `EmbeddingResponseWriter`, handler, routes, on a fake port.
- [x] **Step 3, adapter:** `JsonMembers`, reader, usage reader, writer, adapter, `OpenAiCallLog`; allocation test.
- [x] **Step 4, wiring:** properties, beans, `warmUp` (one sample scan, so the first use is off the request path), `ProviderLifecycleTest`, the disabled-provider start.
- [x] **Step 5, proof:** integration suites, `tools/smoke/embeddings.sh`, no-leak and BlockHound cases.

## 8. Tests

- **Core unit (TDD):** `EmbeddingServiceTest` (provider usage kept, estimate on null, failure passed on, a deployment resolved), `RoutingEmbeddingModelPortTest` (miss is the 400, empty map), `UsageEstimatorTest` (each form, rounding per text), `EmbeddingTypesTest` (`toString` of each input-bearing type prints no content, `RawJson` equality, reserved keys).
- **Budget and lane:** `EmbeddingReplySizeTest` (the ADR's sizes, each input form, cap, overflow, values the provider refuses), `EmbeddingReplyBudgetTest` (whole-budget call, refusal takes nothing, double release, concurrency), `CpuLaneTest` (thread name, fatal error at the deadline, running work on cancel, a full queue refuses the parse and no later stage, later stages close admission, a queued stage never starts, the copy releases its buffer on every path, close), `UpstreamClientPoolTest` (the pending limit's exception), `ReplyHoldTest`.
- **Adapter unit:** `OpenAiEmbeddingAdapterFailureTest` (in-JVM provider: one read on the lane, the guard's edge, fatal scan, lane closed, a reply and an error body under a full lane, the pool's pending limit), `JsonMembersTest` (offsets with whitespace, a string with escapes, nested arrays, a number at the end of an object, a BOM, UTF-16 body), `OpenAiEmbeddingReaderTest` (each rule of the scan table, unknown members at each level, trailing token, allocation bound, a number past `BigDecimal`'s range stays opaque), `OpenAiEmbeddingUsageReaderTest`, `OpenAiEmbeddingRequestWriterTest` (each form, extras in order, `llmgw` gone, absent `encoding_format` stays absent).
- **App (no Docker):** `EmbeddingRequestReaderTest` (gateway rows of section 4, provider rows pass), `EmbeddingResponseWriterTest` (raw bytes unchanged, extras, no `completion_tokens`, `llmgw` only on an estimate), `EmbeddingRouteTest` (401, 405, 413, 415, each `ProviderFailure` as its row), `EmbeddingThreadTest`, `EmbeddingBudgetTest`, `EmbeddingOverloadTest`, `EmbeddingSlicedWriteTest`, `OpenAiEmbeddingAdapterLogTest` (log lines of an unreadable 2xx and a non-2xx, `Retry-After`), `ProviderLifecycleTest`, `ProviderStartupTest`.
- **Integration (Testcontainers, mock provider):** `OpenAiEmbeddingAdapterIntegrationTest` (`hang`, `disconnect`, closed port, one journal entry per failure, cancellation, limit exceeded), `EmbeddingsEndToEndTest` (four forms, base64 and float, journal contents, omitted usage by scenario, full 2048 by 3072 in base64, `openai-java`), `EmbeddingNoLeakIntegrationTest`, a raw-socket case for number literals (the mock normalises its own).
- **Smoke scenarios (`gateway-smoke`):** (1) four forms, both formats, decode base64 as little-endian float32 and compare to the float reply; (2) every row of section 4; (3) Anthropic-mapped model and the OpenAI-disabled start, journal unchanged; (4) omitted usage; (5) 2048 by 3072 in both formats, with time, peak RSS and peak direct memory reported; (6) 8 such calls at once with slow readers, heap and direct memory reported against ADR-GW-05-006 and the excess refused with 503; (7) `hang`, disconnect, closed port; (8) a TRACE run with canaries; (9) no key, revoked key; (10) added latency on a small reply against chat's, and chat's latency during a `[[1],...]` request of 10 MB.
- **Eval (`ai-eval`):** n/a: no threshold, classifier or routing policy, and the mock's vectors are not semantic.
- **Live only, not in acceptance, needs the user's go-ahead:** L1, the real width of a float literal and whether the reply is indented, which checks ADR-GW-05-006's 33 bytes; L2, real 400 bodies for `""`, `[]`, 2049 items and bad `dimensions`; L3, `openai-python` default path through the gateway.

## 9. Success metrics

Added latency on a one-input reply at most chat's (0.9 ms p50, GW-03 run); peak heap per call at most 2 times the reply (smoke 5, 6); 0 vectors altered, by byte comparison in every smoke run.

## 10. ADRs (local)

### ADR-GW-05-001: A separate embedding port
- **Context:** Embeddings have no stream, no messages and another reply. CLAUDE.md asks for one port per capability.
- **Options considered:**
  - A) `EmbeddingModelPort`, one suspend method, with its own call type, service and router.
  - B) `embed` on `ChatModelPort`. Rejected: `AnthropicChatAdapter` would hold a method it can only refuse, and every fake grows.
  - C) A pass-through route with no domain types. Rejected: no team, no usage, nothing for CACHE-02 or RL-02 to hold, and the core port is the only seam those items have.
- **Decision:** A. `input` is typed in all four forms, because GRD-02, RL-02 and the estimator read it. `dimensions`, `encoding_format` and `user` stay in `extras`: no core policy reads them yet (ADR-GW-03-017). CACHE-02 will promote what its key needs. Input-bearing types override `toString`.
- **Consequences:** Buys a boundary kept and a test with no socket. Costs a second router and service, about 40 lines, which RTE-02 will touch twice.
- **Date:** 2026-11-04

### ADR-GW-05-002: Its own adapter and pool
- **Context:** `UpstreamClient` fixes pool and response limit at construction (`OpenAiChatAdapter.kt:39`). Embeddings need a limit 16 times chat's.
- **Options considered:**
  - A) A second adapter class with its own `UpstreamClient`.
  - B) Add `EmbeddingModelPort` to `OpenAiChatAdapter`, one client. Rejected: raises the limit to 256 MB on every chat reply, and a burst of batch calls takes connections from live chat streams.
  - C) One pool, two `WebClient`s. Rejected: `UpstreamClient` would split for no gain over A.
- **Decision:** A: `OpenAiEmbeddingAdapter` in `.openai`, pool `embeddings-openai`. `llmgw` is stripped, there are no retries, every deadline is explicit, and the usage reader is its own. The name does not begin with `openai` because `ProviderLifecycleTest:32` counts lines containing `name=openai`, and `openai-embeddings` would make that 2.
- **Consequences:** Buys isolation of chat from large calls. Costs a second pool per process. The pool does not cap heap: `exchangeToMono` frees the slot once the body is read, before the scan and the write (ADR-GW-05-011).
- **Date:** 2026-11-04

### ADR-GW-05-003: A model on a provider with no embeddings is a 400
- **Context:** "Anthropic does not offer its own embedding model", and its docs point to Voyage AI. `claude-x` resolves to Anthropic. Portkey's Anthropic config maps no `embed`; LiteLLM's `embedding` raises `LiteLLMUnknownProvider` for an unmapped provider, and no Anthropic branch appears in the part read.
- **Options considered:**
  - A) 400 `UnsupportedByProvider`, `param: model`, no upstream call.
  - B) 404 `model_not_found`, the `RoutingChatModelPort` answer. Rejected: the same name works on chat, so "does not exist" misleads.
  - C) Send it to OpenAI anyway. Rejected: it silently answers for another provider, with a model name OpenAI will refuse.
  - D) Voyage AI behind this port. Rejected here: new provider, needs the user's go-ahead (ADR-004).
- **Decision:** A, in the router, from the absence of a port, so core names no provider. With OpenAI disabled the app starts and the route gives the same 400: no embedding adapter is a valid state, not a misconfiguration. Code `unsupported_value`, an existing row.
- **Consequences:** Buys an honest error and a free fallback signal for RTE-02. A Voyage adapter would be one map entry.
- **Date:** 2026-11-04

### ADR-GW-05-004: `encoding_format` is forwarded, never defaulted or converted
- **Context:** The OpenAI default is `float`. openai-python sets `base64` when the caller set nothing and then decodes strings as float32; with an explicit format it returns the reply untouched. So a gateway that changes the format breaks one of the two paths.
- **Options considered:**
  - A) Forward the client's value or its absence, in `extras`.
  - B) Default it to `float`, as LiteLLM's documented fallback does. Rejected: 4 to 6 times the bytes of base64 for no reader, and a changed request.
  - C) Always ask for `base64` and convert for a `float` client. Rejected: a float rendering of our own, CPU per value, and a value that no longer equals the provider's.
- **Decision:** A. A base64 string comes back byte-identical.
- **Consequences:** Buys SDK behaviour unchanged with no code. The gateway does not know a reply's format, which is fine until CACHE-02, and that item can look at the value's first byte.
- **Date:** 2026-11-04

### ADR-GW-05-005: Vectors are opaque, read as byte ranges
- **Context:** ADR-018 reads every body as a tree. At 3072 dimensions by 2048 inputs that is 6.3 million `DecimalNode`s, each with a `BigDecimal`, and a `JsonValue.Number` string each: over 1 GiB by estimate, to carry numbers nobody reads. A tree also rewrites a literal.
- **Options considered:**
  - A) The tree, as chat. Rejected: above.
  - B) Decode to `FloatArray` in the adapter. Rejected: a double rendered by the provider is not a float, so values change, and a rendering must be chosen. Base64 would need decoding too, against ADR-GW-05-004.
  - C) One streaming parse with Jackson, copying each value's byte range.
  - D) Keep the whole body opaque and read only `usage`. Rejected: no per-vector value for CACHE-02, and it fixes OpenAI's wire as the domain.
  - E) A hand-written scanner. Rejected: a JSON lexer to own, for what the parser gives. Jackson's `currentTokenLocation` and `currentLocation` give exact byte offsets on a UTF-8 source.
  - F) Slices of the one body array instead of copies. Rejected: peak heap is the same 2N, since the source is free once the copies exist, and a slice kept by CACHE-02 would pin a whole reply.
- **Decision:** C with copies, into `RawJson`. Well-formedness is still checked, because `skipChildren` lexes every token. Small members go through the existing `readTreeOrNull`, on their slice, which also avoids binding a subtree on a parser whose reader has `FAIL_ON_TRAILING_TOKENS`.
- **Consequences:** This revises ADR-018's "tree model only" ([Revision](../DECISIONS.md#adr-018)) for this one case. Buys constant allocation per value and byte fidelity. New pattern: [opaque-json-value-as-byte-range](../knowledge/patterns/opaque-json-value-as-byte-range.md). CACHE-02 owns decoding.
- **Date:** 2026-11-04

### ADR-GW-05-006: `embeddings.max-response-size` is 256 MB
- **Context:** The default 16 MB (16 MiB in Spring's `DataSize`) fails valid requests. 2048 inputs at 1536 dimensions in base64 is 2048 x 1536 x 4 x 4/3 = 16,777,216 bytes, exactly 16 MiB before any JSON. Floats reach it at about 455 inputs of 1536 at 24 bytes a value. The largest documented output is 3072 (`text-embedding-3-large`), and one-token inputs make 2048 of them valid under the 300,000-token cap.
- **Sizing,** 2048 x 3072 = 6,291,456 values:

  | Format | Bytes a value | Reply |
  |---|---|---|
  | base64 | 16/3 | 33,554,432 B, 32 MiB |
  | float, compact | 24 | 150,994,944 B, 144 MiB |
  | float, indented | 33 | 207,618,048 B, 198 MiB |

  33 is a 23-character literal (`-1.2345678901234567e-05`, 17 significant digits), a comma, a newline and 8 spaces. OpenAI's guide shows an indented sample. No public source states the literal's maximum width; L1 checks it. 256 MiB leaves 29%, or 42.6 bytes a value.
- **Options considered:**
  - A) 256 MB: the worst valid request, with margin, and the existing bound of `ProviderChecks`.
  - B) 40 MB: covers base64, the SDK default. Rejected: a valid float request is a paid 502.
  - C) Stream the reply through without holding it. Rejected: a failure after commit, no `usage` before the end of the body, nothing for CACHE-02, and ADR-019's machinery for a route that is not a stream.
- **Decision:** A. A larger reply stays 502 `upstream_error`.
- **Consequences, memory:** For reply size N a call in flight moves through these stages:

  | Stage | Heap | Direct |
  |---|---|---|
  | Request held through the upstream call, body length B | 2B: the typed input and the outbound array | |
  | Read from the provider | | N: Netty's buffers, joined without a copy |
  | Copy to the body array, on the lane | N | N, until that copy ends |
  | Scan: body and `RawJson` copies | 2N | |
  | Writer: `RawJson` copies and output array | 2N | |
  | Write to the client: output array | N | about one 1 MiB slice |

  Each byte is copied four times: buffers to body, body to `RawJson`, `RawJson` to output, output to a direct slice. The 2B runs beside the first rows and is free by the writer's, so peak per call is at most 2N + 2B of heap and N of direct memory, at different stages. On replies alone that is 396 MiB and 198 MiB at 198 MiB, 512 and 256 at the limit; a typical call, 100 inputs of 1536 indented, is 5 MB, so 10 MB of heap. A body at the 10 MB limit adds 20 MiB. Not in the table: a running parse's tree, by estimate 35 x B for `[[1],...]` (ADR-GW-05-011). These are arithmetic on the code, not measurements: the mock's floats are sparse, so no reply over 35 MB has run (run report, finding 3). An array this size is a humongous allocation under G1. `max-connections` caps only the read; `max-in-flight-bytes` caps the sum of N + 2B (ADR-GW-05-011). The heap this assumes is stated there.
- **Date:** 2026-11-04

### ADR-GW-05-007: Usage on embeddings
- **Context:** The reference's `usage` has `prompt_tokens` and `total_tokens` only. `Usage` has required input and output, and derives the total.
- **Options considered:**
  - A) Reuse `Usage` with `outputTokens = 0`, with a reader and a writer of its own.
  - B) A new `EmbeddingUsage` type. Rejected: COST-01 and RL-02 would handle two usages for one difference.
  - C) Make `completion_tokens` optional in `OpenAiUsageReader`. Rejected: a chat reply without it would stop counting as omitted.
- **Decision:** A. `prompt_tokens` is required and a bad one counts as omitted. `total_tokens` is written from `prompt_tokens`; the provider's is not kept. `completion_tokens` is never written, and is dropped from `usage` extras if a provider sends it. An estimate uses the new overload: text as chat counts it, with no per-message overhead, since there are no messages, and a token array by its length. It is flagged with `x-gateway-usage-estimated`, which non-stream chat uses, and with `llmgw.usage_estimated`, which a chat stream uses: a client reading only the body sees it.
- **Consequences:** A provider whose `total_tokens` differs is overwritten; OpenAI's and the mock's are equal. Non-stream chat has only the header, so the two routes differ by that member.
- **Date:** 2026-11-04

### ADR-GW-05-008: Inputs and vectors are sensitive
- **Context:** An embedding can be inverted to approximate its text, and input is prompt text. `JacksonJsonEncoder` logs what it encodes.
- **Options considered:**
  - A) Treat both as prompt text: no logging, no error content, bytes out.
  - B) Protect input only. Rejected: a vector is derived from it, and costs nothing to protect the same way.
- **Decision:** A. Bytes are written directly, `toString` prints sizes, and error messages are fixed text. A parser's exception is dropped, not wrapped. Public basis for the inversion risk: none cited here; the rule stands on input-derived data alone.
- **Consequences:** A failing reply cannot be debugged from logs; the provider request ID and status are what remain.
- **Date:** 2026-11-04

### ADR-GW-05-009: The gateway validates only what it must type
- **Context:** OpenAI's limits are documented and can change. A check in the gateway that is stricter than the provider breaks a valid call; a check that is equal is a second copy of a rule.
- **Options considered:**
  - A) Check all documented limits at the edge. Rejected: `dimensions` depends on the model, and token limits need a tokenizer, so the check would still be partial. It saves a round trip only on a failing call.
  - B) No checks at all, `input` as `JsonValue`. Rejected: GRD-02 and the estimator need it typed.
  - C) Refuse only what cannot be typed; relay the rest.
- **Decision:** C, section 4's table. `[]` types as empty `Texts` and goes upstream. The 10 MB body limit bounds the bytes read whatever the item count; time is bounded apart, on the lane (ADR-GW-05-012): `[[1],[1],...]` at 10 MB took 316 to 474 ms and about 720 MB of allocation in a standalone measurement.
- **Consequences:** An empty input costs a round trip but no money. The mock refuses the same rows, so tests prove the relay. A client gets a fixed `message` on a relayed row, with OpenAI's `code` and `param`.
- **Date:** 2026-11-04

### ADR-GW-05-010: The reply scan leaves the event loop
- **Context:** After `exchange`, the adapter resumes on a `reactor-http-*` thread shared with chat streams. A scan of 198 MiB at an assumed 300 to 500 MB/s is about half a second on that loop; even 5 MB is 10 ms or more, near the whole 15 ms overhead budget of pillar 2, for every other connection on it.
- **Options considered:**
  - A) Scan inline, as chat reads its tree. Rejected: above.
  - B) `Mono.fromCallable { scan }.subscribeOn(scheduler).awaitSingleOrNull()`, on every 2xx. `OrNull`: the reader answers null for a body it cannot read, which the Mono carries as empty.
  - C) B above a size threshold only. Rejected: a constant to tune, to save a hop of microseconds on a call of 50 ms or more.
  - D) `withContext(Dispatchers.Default)`. Rejected: its threads are outside BlockHound's watch ([gotcha](../knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)).
  - E) `Schedulers.parallel` as the scheduler, as a first version did. Rejected: large scans queue in front of the Reactor timers chat's deadlines run on, and a scan held no deadline.
- **Decision:** B, on the adapter's `CpuLane` (ADR-GW-05-012). `coRouter` handlers run `Unconfined`, so the handler resumes on that thread and the writer runs there too with no second hop. `EmbeddingThreadTest` holds both.
- **Consequences:** Buys the latency budget for chat, and timers that large scans cannot delay; `Mono.timeout`'s own timer still runs on `parallel`, as one small task. The throughput figure is assumed; smoke 5 measures it.
- **Date:** 2026-11-04

### ADR-GW-05-011: A process-wide budget of reply bytes, refused rather than queued
- **Context:** The pool does not cap memory: a connection returns to it when the body is read, while 2N of heap lives on through the scan and the write, and a 198 MiB reply takes 17 s to drain at 100 Mbit/s. Slow clients could hold more replies than any `max-connections` allows, and an OOM takes chat down with it. The reply's real size is known only after it is paid for, so the limit must act on a bound computed from the request.
- **Options considered:**
  - A) A budget in bytes: reserve the request and the reply's worst case, release when the response write ends, refuse with 503 when full.
  - B) Hold one of `max-connections` permits through the write. Rejected: a permit counts a 5 MB call and a 198 MiB call the same, so a safe count starves typical traffic.
  - C) Queue when full. Rejected by the user, 2026-11-04: "No queueing". A queue adds a wait that can pass the client's deadline, and holds the request meanwhile.
  - D) Reuse `ProviderUnavailable` for the 503. Rejected: "the provider cannot be reached" is false, and RTE-02 falls back on `ProviderFailure`, which would send the same load to a second provider.
  - E) Reserve in `EmbeddingService`. Rejected: its call ends before the write, and the reservation must outlive it.
- **Decision:** A, the user's decision of 2026-11-04. `worstCaseReplyBytes` in core, `EmbeddingReplyBudget` as an atomic counter in core, one instance per process, reservations in the handler, release from `EmbeddingReplyFilter`'s `doFinally` (section 5). New variant `GatewayError.Overloaded`, row in section 4. A set `max-in-flight-bytes` below `max-response-size` stops the start: with the cap on a reservation, that is what lets one large call run alone on a free budget.
- **Revised 2026-11-05: what is reserved.** The reply alone left the heap this ADR claimed unguarded ([bug](../knowledge/bugs/embedding-request-memory-is-outside-the-budget.md)). A call now also reserves 2 x its body length, before `cpu.run`, so queued parses are counted, and holds it with the reply's bytes to the end of the write. Two reservations in one `ReplyHold`, each released once. A full budget therefore refuses a bad body too, before it is parsed. The reply's reservation is trimmed by what its call holds, so a body cannot stop a call at the budget from running alone.
- **Revised 2026-11-05: the default.** `max-in-flight-bytes` unset is 25% of `Runtime.maxMemory()`, so the budget follows the heap it runs on: a fixed `512MB` assumed a heap nothing guaranteed, and a default JVM in a 2 GiB container has 512 MiB. A set value above 50% stops the start, naming the property. Options: a fixed default with a startup check, rejected, as it fails the small heaps a default must fit; a percentage alone, chosen. An unset budget below `max-response-size`, on a heap under 1 GiB, starts with a WARN: a call priced above the budget is then always refused, before it is paid for, since the reply could not be carried. A worst float call, 198 MiB, needs a heap of 792 MiB.
- **Heap arithmetic.** With budget C and everything in flight at its reservation, the sum of N + 2B is at most C, or C plus one call's 2B when a call at the budget was trimmed. Peak heap per call is 2N + 2B (ADR-GW-05-006), so the in-flight total is at most 2C, and direct memory at most C, inside the JVM's default `MaxDirectMemorySize` of the maximum heap, plus about one slice a call being written. At 25%, 2C is half the heap. The other half holds chat, the JVM, bodies still being read and the trees of running parses: at most `cpu-threads` of them, by estimate 350 MB each for `[[1],...]` at 10 MB, so 1.4 GB at the default of 4.
- **Heap assumption.** 2 GiB, as the smoke run: C is 512 MiB, 2C is 1 GiB, and 1 GiB is left, enough for any traffic but four worst-case parses at once, which by estimate need 1.4 GB. 4 GiB covers those too: C is 1 GiB, 2C is 2 GiB, and 2 GiB is left. At the 50% ceiling 2C is the whole heap, so nothing is left: it is a limit, not a target. Size a set value as `(heap - everything else) / 2`. All of this is arithmetic with the 33 bytes a value of ADR-GW-05-006 and an estimated tree size; none of it is measured at 198 MiB.
- **Sizing,** on a 2 GiB heap, 536,870,912 bytes:

  | Reservation | Each | At once |
  |---|---|---|
  | Worst float batch, 2048 x 3072 x 33 | 198 MiB | 2, with 116 MiB left |
  | At the `max-response-size` cap | 256 MiB | 2 |
  | Full base64 batch, 2048 x 3072 x 16/3 | 32 MiB | 16 |
  | 100 inputs, no `dimensions`, 100 x 3072 x 33 | 9.67 MiB | 52 |
  | One input, no `dimensions`, 3072 x 33 | 99 KiB | 5,295, so `max-connections` binds first |
  | A body at the 10 MB limit, before its reply | 20 MiB | 25 |
- **Consequences:** Buys a bound on reply memory and fail-fast instead of an OOM. Costs over-reservation: a call with no `dimensions` is priced at 3072 and 33 bytes, so 100 inputs of 1536 reserve 9.67 MiB for a 5 MB reply. It bounds N, the reply, by estimate: a reply wider than 33 bytes a value is served and undercounted, which L1 checks, and per-item JSON and base64 padding are unpriced, under 1 KiB an item. Three limits remain: per process, so replicas multiply it; no per-team share before RL-02; a stage still running after a cancel holds memory for under a second after its release.
- **Revised 2026-11-05: the pool's pending limit.** `UpstreamClient` lets 2 x `max-connections` wait, and the next call failed at once as 503 `provider_unavailable`, with no `retry-after` and an ERROR each. `UpstreamClientPoolTest` reads the cause: `WebClientRequestException` around `PoolAcquirePendingLimitException`, which the smoke run had only inferred. It is this ADR's option D by another route: the gateway's own queue is full, no provider failed, and RTE-02 would send the load to a second provider. On the embeddings path the adapter answers `EmbeddingResult.Busy`, so `Overloaded` with `Retry-After`, no `Provider call failed` line, and the funnel's one refusal line. Options: map it in `TransportFailures`, rejected, since chat shares that map and keeps its behaviour (GW-16); raise the limit, rejected, as it moves the same refusal. `PoolAcquireTimeoutException` stays `provider_unavailable`: 5 s with every connection held means the provider is slow, which a fallback can help.
- **Revised 2026-11-05: the log.** This ADR accepted an ERROR with a stack trace per refusal, as every 5xx through the funnel writes; 18,090 refusals came to 72,476 lines and 6.75 MB (run report, finding 4). A refusal is back-pressure, not a failure: the funnel now writes one line, no stack trace, WARN with the running total for the first of each thousand and DEBUG for the rest. A counter and no clock: one `AtomicLong`, and OBS-02 owns rates.
- **Date:** 2026-11-04

### ADR-GW-05-012: One bounded CPU lane, owned by the adapter and borrowed by the handler
- **Context:** Work that grows with a body must leave the event loop: the scan, and also the request. `[[1],...]` at 10 MB held a loop for 316 to 474 ms in a standalone measurement, and the provider then refuses it for having over 2048 items. `parallel` is no home for it (ADR-GW-05-010), and a JVM-fatal error in a callable sends no signal, so a stage with no deadline can hang a call while holding its body.
- **Options considered:**
  - A) A `Schedulers.newParallel` owned by the adapter, disposed at `close()`, sized by `embeddings.cpu-threads`, lent to the handler through the core port `EmbeddingCpuPort`.
  - B) `Schedulers.boundedElastic`. Rejected: shared, and its threads may block, so BlockHound stops watching this path.
  - C) One scheduler bean owned by Spring and passed to the adapter. Rejected by the user's decision of 2026-11-04: the adapter owns it.
  - D) A second scheduler at the edge. Rejected: two pools means twice the threads for one bound on CPU.
- **Decision:** A, `CpuLane`. Non-blocking threads, so BlockHound still watches the path; daemon, so a stuck stage cannot hold the JVM. Every stage has a deadline after `subscribeOn`, `embeddings.request-timeout`. All calls pay the hops: the user allowed a small request to skip them only if the code stayed simple, and one path is simpler. Chat never touches the lane. When OpenAI is off there is no adapter, and `embeddingCpuPort` builds a one-thread lane for the route's refusals; Spring's close is safe on either, as `dispose` is idempotent.
- **Sizing:** 4 threads. At the assumed 300 to 500 MB/s that is 1.2 GB/s or more of scan, above what 1 Gbit/s can deliver, and a worst-case parse leaves three threads free. It also bounds live request trees to 4. No public source.
- **Revised 2026-11-05: the queue.** `Schedulers.newParallel` queues without limit, so the earlier "bounded by the deadline" meant 120 s of waiting bodies. `CpuLane` now counts stages in flight and refuses past `cpu-threads` plus `cpu-queue-size`, 256 by default: bodies that wait are in the budget, so this bounds tasks and time: 256 worst parses at about 400 ms on 4 threads is 26 s of work, and they would need 5 GiB of reservations, so the budget binds first. A counter and not a bounded executor, which would give up `newParallel`'s non-blocking threads and their fatal-error handling. Refusal and a closed lane are one `RejectedExecutionException`, and both reach the client as 503 `gateway_overloaded`, not 500: the gateway could not take the work and no provider failed, so the adapter answers `EmbeddingResult.Busy`, not a `ProviderFailure` RTE-02 would fall back on. A cancelled stage frees its count at once; if it was running it still holds its thread until it ends.
- **Revised 2026-11-05: the reply's copy.** `ByteArrayDecoder` allocated and copied up to 256 MB on the event loop ([bug](../knowledge/bugs/embedding-reply-read-copies-whole-body-on-the-event-loop.md)). The embedding path decodes to the joined `DataBuffer`, which costs no copy, and `CpuLane.copy` makes the array on the lane, inside `exchangeToMono` and so inside the exchange's timeout: in the chain, with no gap between the decoder and the lane where a cancel could drop a buffer. Exactly one of the copy and a cancel takes the buffer, and it releases it. Chat keeps `ByteArrayDecoder`.
- **Revised 2026-11-05: the queue is admission only.** A refusal could come after the provider answered: 1,536 of 27,233 503s were `completed` in the journal (run report, R1), so a call was billed, dropped, and retried at a second bill. `cpu-queue-size` now applies only to the request's parse, through `run`. Every later stage, the upstream write, the reply's copy, the scan and the error read, goes through `runAdmitted` or `copy` and is never refused for a full queue. This replaces "stages in flight" in the queue's revision above, and limits its one `RejectedExecutionException`: past admission that exception is a closed lane, so shutdown only. Options: refuse up to the upstream write and pass only later stages, rejected, a second rule for a stage whose call already holds its reservation; keep the refusal and document the loss, rejected, a paid reply thrown away is the worst answer an overload can give.
- **The bound on later stages.** The in-flight budget, not `cpu-queue-size`. Each such stage belongs to a call that holds its reservations, and a call has one stage on the lane at a time, so stages waiting are at most the calls in flight and the bytes they hold are inside `max-in-flight-bytes`, within ADR-GW-05-011's limits on that sum. The bound is on bytes: a count follows from the smallest reservation, 5,295 calls of one input with no `dimensions` on a 2 GiB heap, and more with a small `dimensions`. By estimate 512 MiB of reservations is under 1 s of scan at the 1.2 GB/s of this ADR's sizing, and they are worst-case prices. Later stages count towards the counter, so a backlog closes admission until it drains, which is the back-pressure. A later stage keeps its deadline: one that waits past `request-timeout` ends as 504, still a paid reply lost, and that takes a backlog about 100 times this estimate.
- **On cancellation:** a queued stage never starts (`CpuLaneTest`). A running stage is not stopped: the Reactor task is disposed, but neither the parser nor the scan polls for an interrupt, so it finishes, under 1 s at the limit by estimate, and its result is dropped (`CpuLaneTest`). An `OutOfMemoryError` on a lane thread is logged by Reactor; the thread survives.
- **Consequences:** Buys chat's loops and timers. Costs four hops a call, measured by smoke 10, and a deadline on the lane adds to the exchange's, so the worst call takes up to 2 x `request-timeout`. The adapter exposes `lane`, a seam a second embedding adapter would have to share or replace.
- **Date:** 2026-11-04

### ADR-GW-05-013: A reply is written as 1 MiB slices
- **Context:** Netty copies a heap buffer to a direct one on write, on the event loop ([gotcha](../knowledge/gotchas/netty-copies-a-heap-buffer-to-direct-on-write.md)). One buffer of 198 MiB is one direct allocation of 198 MiB and one copy of that size, on a loop shared with chat, held until the client has read it.
- **Options considered:**
  - A) A `Flux` of 1 MiB views of the one array, with `Content-Length` set.
  - B) Stream vectors from `RawJson` without the output array. Rejected: saves N of heap for a generator of framing, and `Content-Length` would need a counting pass.
  - C) Smaller slices. Rejected: 1 MiB copies in well under a millisecond at memory speed, and 64 KiB would be 3,168 writes for the worst reply.
- **Decision:** A. A view costs no bytes of its own.
- **Revised 2026-11-05: pacing.** The first version said Reactor Netty's send stops pulling while the channel is not writable. It does not: `MonoSendMany` writes up to 128 items past unwritable ([gotcha](../knowledge/gotchas/reactor-netty-send-writes-its-whole-prefetch-past-unwritable.md)). `writeAndFlushWith` with one `Mono` a slice does not change that either: `sendGroups` flattens the groups into one `send`, and a client that read nothing still had all 33,554,720 bytes written. Demand cannot pace this, so the source does. `ReplyWritePacer`, a handler added for the reply and removed by Reactor Netty at its end, holds each slice until `channelWritabilityChanged` says the channel can take more. The source runs on the channel's event loop, where `MonoSendMany` writes inline, so the check after a slice sees that slice's bytes. Options: `writeWith` or `writeAndFlushWith` alone, rejected, above; `reactor.netty.send.maxPrefetchSize`, rejected, global to every route; sending through `HttpServerResponse` slice by slice, rejected, past Spring's commit of the headers; polling `isWritable` on a timer, rejected, a tick per stalled client and a cap on throughput. A reply of one slice skips the pacer, so a small call pays nothing.
- **Consequences:** Direct memory while writing is about one slice per call, and the loop is held for one slice at a time. `EmbeddingSlicedWriteTest` proves the size of each write at the socket, and that a client that reads nothing holds at most 2 MiB in the channel of a 32 MiB reply. It uses Netty types in `gateway-app`, as `ClientStallHandler` does. Peak direct memory through the app is smoke 5's to report.
- **Date:** 2026-11-04

### ADR-GW-05-014: A non-2xx body is read once, under a 64 KiB guard
- **Context:** ADR-GW-05-002 raises the limit for every reply, errors included, to 256 MB. The classifier and the log each read the error body as a tree, inline. OpenAI's error object is under 1 KiB; a body far larger is a proxy's page or a fault.
- **Options considered:**
  - A) Over 64 KiB, skip the read and use `ProviderErrorFields.NONE`; at or under it, read once on the lane and give the fields to both the log and the classifier.
  - B) Read any size, on the lane. Rejected: a tree of 256 MB for two short strings.
  - C) Read inline under the guard. Rejected: a second rule about threads, to save a hop on a failed call.
- **Decision:** A, the user's decision of 2026-11-04. A body over the guard gives the row for its status with no `code` or `param`; headers still apply, so `Retry-After` survives.
- **Consequences:** Over the guard a 429 cannot be told to be a quota error, and a 404 is `UpstreamError` rather than `ModelNotFound`. The size is this project's judgment. `OpenAiFailureClassifier` gains a `classify` overload taking fields; chat's calls are unchanged.
- **Date:** 2026-11-04

## 11. Log

### 2026-11-04: Spec
- **Who:** gateway-architect
- **Done:** Spec from the public sources in `references`, each fetched this session. LiteLLM's `main.py` was read from character 200,000 on, which holds `embedding`.
- **Decisions:** ADR-GW-05-001 to -010; ADR-018 revised; pattern `opaque-json-value-as-byte-range` added.
- **No public source:** `request-timeout` 120 s; `max-connections` 64; the 33-byte literal; scan throughput; the 1 GiB tree estimate.
- **Follow-ups for `docs-update`, not applied here:** a new `[?]` row, "Embeddings through Voyage AI: does the user go ahead with a new provider (ADR-004)? Anthropic offers no embedding model, so an Anthropic-only start has none"; `systems/provider-adapters` (port, reply scan as the exception to "every body is read through `exactTreeReader`", properties); CLAUDE.md built state and systems rows.
- **Next:** Step 1.

### 2026-11-04: Review findings
- **Who:** gateway-architect's spec, changed with the code by the implementing agent.
- **Done:** Budget, lane, sliced write, request off the loop, error-body guard, the missing tests. The user's decisions of 2026-11-04 are recorded in their ADRs.
- **Decisions:** ADR-GW-05-011 to -014; ADR-GW-05-002, -006, -009, -010 revised.
- **No public source:** `cpu-threads` 4, `busy-retry-after` 5 s, the 64 KiB guard.
- **Follow-ups for `docs-update`, not applied here:** the entries in `docs/knowledge/bugs/`, `systems/provider-adapters`, the `gateway_overloaded` row wherever error rows are listed, glossary entries for the budget and the lane.

### 2026-11-05: Run findings and closing
- **Who:** implementing agent.
- **Done:** The findings of the [smoke run](../knowledge/runs/2026-11-05-smoke-embeddings.md) and the review bugs. A reply is paced by channel writability (`ReplyWritePacer`), because `writeAndFlushWith` alone left the test red. A call reserves twice its body before the parse. The reply's copy runs on the lane. `max-in-flight-bytes` defaults to 25% of the heap and refuses over 50%. `CpuLane` has a bounded queue, and a refusal or a closed lane is 503. A refusal logs one quiet line. `anthropic.sh`'s extractor is fixed, and `check_same` and an empty-file guard stop the smoke scripts passing on nothing. Living docs.
- **Decisions:** ADR-GW-05-006, -011, -012, -013 revised. New property `cpu-queue-size`. An unset budget below `max-response-size` starts with a WARN and refuses calls priced above it.
- **No public source:** `cpu-queue-size` 256, one WARN a thousand refusals.
- **Not done:** the smoke scenarios were not rerun on this code, since the apps were not started. The run report predates these fixes, and they change the write path and the order of a 503 and a 400 under a full budget. Section 3's smoke criterion stays open, so the item stays `in-progress`. Section 9's heap figure is a measurement goal, not a criterion, and is unmeasured: the mock's sparse floats never reached 198 MiB (OPS-06).
- **Diagrams:** `sequence-chat-completion` is still the flow with section 5's differences; none changed.
- **Next:** `HANG=1 bash tools/smoke/anthropic.sh`, `embeddings.sh`, smoke 5 and 6 on this tree, then close the item.

### 2026-11-05: Rerun findings R1 to R3
- **Who:** implementing agent.
- **Done:** R1: `cpu-queue-size` applies only to the request's parse; later stages run through `CpuLane.runAdmitted` or `copy`, count towards the queue and are never refused for it. A rejection in the adapter is now a closed lane. R2: `UpstreamClientPoolTest` confirmed `PoolAcquirePendingLimitException` as the cause; on this path it is `EmbeddingResult.Busy`, so `Overloaded` with `Retry-After`, logged as a refusal. R3: stated in section 4, no code change.
- **Red first:** on the unchanged code, `a full lane never drops a reply the provider has answered` (65 of 200 reached the provider), `a full lane never drops a provider's error body` (`Busy`), `the pool's pending limit answers Busy` (`ProviderUnavailable`), `a full queue never refuses a reply's copy` (`RejectedExecutionException`). `a refusal for the pool's pending limit logs no provider failure` was written with the fix and shown red by reverting the fix for one run; the same run put the scan and the error read back on `run`, and both full-lane tests went red.
- **Decisions:** ADR-GW-05-011 and -012 revised. `PoolAcquireTimeoutException` stays `provider_unavailable`.
- **Follow-up:** chat shares R2 through `TransportFailures`, on OpenAI and Anthropic: GW-16.
- **Not done:** the apps were not started, so neither finding was rerun over HTTP. The item stays `in-progress`.

### 2026-11-05 — orchestrator: closed

- **Verified:** the last smoke ran on the final code: R1 closed (0 of 757,388 503s completed at the mock), R2 closed (42 of 42 `gateway_overloaded` with `Retry-After`, no stack trace), `embeddings.sh` 243 PASS and `chat-completions.sh` 60 PASS, 0 FAIL ([run](../knowledge/runs/2026-11-05-smoke-embeddings.md)).
- **Known and open, none an acceptance criterion:** section 9's 2x heap figure is unmeasured on dense replies (OPS-06). Parse trees are not reserved: four worst-case `[[1],...]` parses are estimated at 4 GiB, reachable only with a team key, until RL-01 limits a team. R4: beside large parses, small embedding calls fell 46 to 86% with p99 up to 6.2 s under saturation; cause inferred, measured under OPS-06.
