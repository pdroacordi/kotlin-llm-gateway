---
id: FND-03
title: mock-provider (OpenAI- and Anthropic-shaped fake with configurable latency, errors, streaming and usage; request counter and request log)
status: done   # not-started | in-progress | done | blocked | skipped
needs: [FND-01]
owner: —
updated: 2026-11-04
references:
  - https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
  - https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events
  - https://developers.openai.com/api/reference/resources/embeddings/methods/create
  - https://developers.openai.com/api/reference/resources/models/methods/list
  - https://developers.openai.com/api/docs/guides/error-codes
  - https://developers.openai.com/api/docs/guides/rate-limits
  - https://developers.openai.com/cookbook/examples/how_to_count_tokens_with_tiktoken
  - https://github.com/openai/openai-python/blob/main/src/openai/_streaming.py
  - https://github.com/openai/openai-python/blob/main/src/openai/lib/_parsing/_embeddings.py
  - https://platform.claude.com/docs/en/api/messages/create
  - https://platform.claude.com/docs/en/build-with-claude/streaming
  - https://platform.claude.com/docs/en/api/errors
  - https://platform.claude.com/docs/en/api/rate-limits
  - https://html.spec.whatwg.org/multipage/server-sent-events.html
  - https://github.com/spring-projects/spring-framework/blob/7.0.x/spring-web/src/main/java/org/springframework/http/codec/ServerSentEvent.java
  - https://github.com/spring-projects/spring-framework/blob/7.0.x/spring-web/src/main/java/org/springframework/http/codec/ServerSentEventHttpMessageWriter.java
  - https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/http/ReactiveHttpOutputMessage.html
  - https://docs.spring.io/spring-framework/reference/languages/kotlin/coroutines.html
  - https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html
  - https://docs.spring.io/spring-boot/reference/features/kotlin.html
  - https://docs.gradle.org/current/userguide/how_to_share_outputs_between_projects.html
  - https://java.testcontainers.org/features/creating_images/
  - https://github.com/docker-library/docs/blob/master/eclipse-temurin/README.md
  - https://github.com/reactor/BlockHound
  - https://wiremock.org/docs/simulating-faults/
  - https://wiremock.org/docs/verifying/
  - https://wiremock.org/docs/response-templating/
  - https://arxiv.org/abs/0902.2206
  - https://datatracker.ietf.org/doc/html/draft-eastlake-fnv
---

# FND-03: mock-provider

## 1. Context and goal

Every gateway item from GW-01 on needs an upstream to call, and CLAUDE.md forbids a real one in tests and CI ("Tests never call a real provider"; live tests are tagged and need the user's go-ahead). The items that follow need more than a canned 200: GW-01 needs provider-correct bodies and errors to translate, GW-02 a stream it can cancel mid-way and watch being cancelled upstream, GW-03 the Anthropic event sequence, RL-02 stream usage it can reconcile, RTE-02 a model that fails a set number of times and then recovers, CACHE-01 proof that a hit reached no provider, GRD-02 proof of what the provider actually received, and OPS-01 a fixed, cheap upstream latency to subtract from gateway latency.

FND-03 builds `mock-provider`: a standalone Spring Boot WebFlux service on port 8090 that answers the OpenAI paths (`/v1/chat/completions`, `/v1/embeddings`, `/v1/models`) and the Anthropic path (`/v1/messages`) with deterministic content and usage, frames streams byte for byte as the providers document them, and takes behaviour (latency, errors, faults, content, usage) from per-model scenarios set over an admin API under `/__mock`. It counts every request and keeps a bounded, redacted request log, also under `/__mock`. The same jar runs as a process for smoke and load runs and as a Testcontainers container for integration tests.

It serves no VISION pillar directly; it is the test double that lets pillars 1 (drop-in: adapters tested against provider-exact shapes), 2 (low overhead: OPS-01 measures against a fixed upstream latency) and 6 (resilient: fallback and breakers exercised with scripted failures) be proven without paid calls. It must not drift into a provider simulator: it does not model quality, real tokenization, real rate limiting or any feature no listed consumer needs (section 2, Out).

## 2. Scope

### In
- New module `mock-provider` (root package `io.github.pdroacordi.llmgateway.mockprovider`), applying `llmgw.spring-app`; role changed from `tool` to `app` in the module table (ADR-FND-03-003).
- OpenAI surface: `POST /v1/chat/completions` (non-stream; stream with and without `stream_options.include_usage`), `POST /v1/embeddings` (string, string array, token array, token-array array; `dimensions`; `encoding_format` `float` and `base64`), `GET /v1/models`.
- Anthropic surface: `POST /v1/messages` (non-stream and stream: `message_start`, `content_block_start`, `ping`, `content_block_delta`, `content_block_stop`, `message_delta`, `message_stop`, and `error`).
- Deterministic content (modes `lorem`, `echo`, `fixed`), deterministic approximate usage, provider-shaped finish reasons (`stop`/`length`, `end_turn`/`max_tokens`), Anthropic and OpenAI cache-token fields.
- Behaviour control through per-model scenarios: latency (time to first token, per-token, jitter), injected HTTP errors with provider-correct bodies and headers (`retry-after`, `retry-after-ms`, any `x-ratelimit-*` / `anthropic-ratelimit-*`), faults (`hang`, `stall`, `disconnect`, `stream_error`, `malformed`), content and usage overrides (including omitting usage). Ordered steps consumed atomically, then a fallback behaviour; a `*` scenario for every model without its own.
- Request counter (arrival sequence, per route and model) and a bounded request journal with redacted headers, credential fingerprint and capped bodies; filters by route, model, arrival sequence and header value; reset.
- Credential check: any non-empty bearer token or `x-api-key` is accepted; a missing one is 401 in the provider's shape.
- One error mapper in `.mockprovider.error` (ARCH-10) writing provider-shaped errors for framework failures.
- `mock-provider/Dockerfile` and a consumable Gradle configuration exposing the boot jar, so consumers build a Testcontainers image from it (ADR-FND-03-002). A container test proves the image.
- BlockHound in `mock-provider`'s tests (ADR-FND-03-009).
- Doc edits listed in section 7, step 7 (for the implementer).

### Out
- Tool calls in responses (OpenAI `tool_calls`, Anthropic `tool_use` blocks and `input_json_delta`). Requests carrying `tools`, `tool_choice`, `role: tool` messages, assistant `tool_calls` with `content: null` or `tool_result` blocks are accepted and ignored for generation. GW-03 and GW-06 extend the mock when they need scripted tool calls (the content model has room for it: section 5, Generation).
- Images, audio, documents and any non-text content part (accepted, ignored for generation and token counts); `n` other than 1 (400); `logprobs` (always `null`); `stop`/`stop_sequences` matching; OpenAI `obfuscation` padding; thinking blocks.
- OpenAI Responses API, legacy Completions, Assistants, batches, files; Anthropic `/v1/models`, `/v1/messages/count_tokens`, batches. Unknown paths answer 404 in the provider shape.
- Real tokenization, real rate limiting, semantic embeddings (the `semantic-cache` eval uses the local models of the stack, `ai-eval` skill).
- A `deploy/compose.yaml` service for the mock (FND-02 or OPS-01 adds it from the same Dockerfile), Testcontainers base classes for gateway modules (FND-02), the consumer-side Gradle wiring in `gateway-app` or `gateway-providers` (GW-01).
- OpenTelemetry, metrics and structured logs in the mock.

## 3. Acceptance criteria

All ticked before the row becomes `[x]`. "Deliberate violation" checks are made in the working tree, observed failing, and reverted before commit.

- [x] `./gradlew projects` lists `mock-provider`; it applies only `llmgw.spring-app`; the root build has `kover(project(":mock-provider"))` and excludes the exact class `io.github.pdroacordi.llmgateway.mockprovider.MockProviderApplicationKt`.
- [x] `Modules.kt` lists `mock-provider` with role `APP` and empty `dependsOn`; `./gradlew :architecture-tests:test` passes (ARCH-1 to ARCH-10). Deliberate violation: an `import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError` in any `mock-provider` file fails ARCH-9 naming the file.
- [x] `./gradlew :mock-provider:bootRun` starts on 8090; `curl -s localhost:8090/actuator/health` returns `{"groups":["liveness","readiness"],"status":"UP"}` (or the same `status` with Boot's default members); the app stops in under 4 s of a stop signal with a request in a `hang` fault (`server.shutdown: immediate`, `spring.reactor.netty.shutdown-quiet-period: 0s` and a 3 s Netty lifecycle timeout; `ShutdownIntegrationTest`). Revised 2026-11-03 from "within 2 s": `immediate` alone took about 5 s (Reactor Netty's 3 s dispose wait plus a 2 s quiet period, [gotcha](../knowledge/gotchas/reactor-netty-stop-waits-dispose-timeout-and-quiet-period.md)), and the lifecycle timeout, first 1 s, was raised to 3 s in review because it also bounds the startup bind on a loaded CI runner; 4 s keeps a margin above it.
- [x] Determinism: the same chat request sent twice returns the same `choices[0].message.content` and the same `usage`, and different `id`s; changing one character of a message, or the model, changes the content. Same for `/v1/messages`. Streaming and non-streaming forms of one request yield the same concatenated text and the same usage.
- [x] OpenAI stream framing: with content mode `fixed` and text `"Hi there"`, a fixed `Clock` and arrival sequence 1, the raw response body of a stream with `include_usage: true` equals the golden file `openai-chat-stream-include-usage.sse` byte for byte: every event is `data: <compact JSON>\n\n`, a first chunk with `delta: {"role":"assistant","content":""}`, one chunk per piece, a chunk with `delta: {}` and `finish_reason: "stop"`, a final chunk with `choices: []` and the `usage` object, every earlier chunk carrying `"usage":null`, then `data: [DONE]\n\n`. Without `include_usage`, no chunk has a `usage` member (golden `openai-chat-stream.sse`). `Content-Type` is `text/event-stream` with UTF-8.
- [x] Anthropic stream framing: same conditions, golden `anthropic-messages-stream.sse`: `event: <type>\ndata: <compact JSON>\n\n` for `message_start` (empty `content`, `stop_reason: null`, usage with `input_tokens`, both cache fields, `output_tokens: 1`), `content_block_start`, `ping`, one `content_block_delta` (`text_delta`) per piece, `content_block_stop`, `message_delta` (`stop_reason`, `stop_sequence: null`, `usage: {"output_tokens": N}`), `message_stop`.
- [x] Not buffered: with `perTokenMs: 200` and 5 pieces, a WebClient reading the raw stream, timing each event from the start of the request, receives piece k no earlier than 200 × k ms, the last content event at least 600 ms after the first (the spread a buffered stream cannot have), and `[DONE]` no earlier than 1000 ms (`LatencyIntegrationTest`). Lower bounds only. Revised 2026-11-03 from "the first content event within 150 ms of the role chunk and before 400 ms": upper bounds on arrival times flake on a loaded CI runner (review finding 4), while a wait never ends early, so lower bounds and the spread prove flushing without timing margins.
- [x] Usage invariants (section 5, Usage) hold in every integration response: OpenAI `total_tokens = prompt_tokens + completion_tokens`, `completion_tokens` = number of content pieces; Anthropic `input_tokens + cache_read_input_tokens + cache_creation_input_tokens` = the OpenAI `prompt_tokens` of the same conversation; scenario `usage.omit` removes `usage` from the non-stream body and the usage chunk (OpenAI) or the `usage` members (Anthropic).
- [x] Embeddings: same input gives the same vector; vectors have L2 norm 1 ± 1e-6 and the requested `dimensions` (default 1536); `encoding_format: base64` decodes as little-endian float32 to the same values as `float` within float32 precision; `usage.prompt_tokens = total_tokens` = sum of approximate tokens; empty string gives 400.
- [x] `GET /v1/models` returns `{"object":"list","data":[...]}` with one `{"id","object":"model","created","owned_by":"mock-provider"}` per configured model.
- [x] Error injection: `PUT /__mock/scenarios/mock-chat` with steps `[{"error":{"status":429,"retryAfterSeconds":2,"headers":{"x-ratelimit-remaining-requests":"0"}},"times":2}]` makes exactly two of ten concurrent chat requests to `mock-chat` return 429 with `retry-after: 2`, the extra header and body `{"error":{"message":...,"type":"requests","param":null,"code":"rate_limit_exceeded"}}`; the other eight return 200. Each status in section 4's defaults table returns that row's body on the OpenAI and the Anthropic route (parameterized test); Anthropic bodies carry `"type":"error"` and a `request_id` equal to the `request-id` header.
- [x] Faults: `hang` with a 500 ms client timeout leaves a journal entry with outcome `client_cancelled` within 2 s; `stall` after 3 pieces sends 3 pieces and then nothing until the client gives up (outcome `client_cancelled`, `piecesSent: 3`); `disconnect` after 3 pieces makes the client fail with a premature-close error after receiving 3 pieces (outcome `aborted`), and non-stream `disconnect` sends status 200, headers and part of the body, then closes; `stream_error` sends the provider's in-band error event (OpenAI `data: {"error":{...}}`, Anthropic `event: error`) and ends the stream; `malformed` returns 200 with a body that is not valid JSON (non-stream) or one `data:` event that is not valid JSON (stream).
- [x] Journal and counter: a chat request with `Authorization: Bearer sk-test-abc`, `traceparent` and `x-client-request-id: c-1` produces exactly one journal entry whose `headers.authorization` is `["[redacted]"]`, whose `traceparent` and `x-client-request-id` keep their values, whose `credential` is `{"scheme":"bearer","fingerprint":<first 8 hex of SHA-256 of "sk-test-abc">}`, and whose `body` is the request body; `GET /__mock/requests?header=x-client-request-id:c-1` returns only it; `GET /__mock/stats` shows `lastSeq` advanced by one and the `(openai.chat, mock-chat)` count by one. No response, log line or journal field contains `sk-test-abc` (asserted over the captured output and the admin responses). A request to an unknown path also advances `lastSeq` and is journaled with route `unknown`. After `POST /__mock/reset`, counts, scenarios and the journal are empty and `lastSeq` is unchanged; an entry from a request that started before the reset never appears after it.
- [x] Credentials: no `Authorization` and no `x-api-key` gives 401 (`invalid_request_error` / `invalid_api_key` on OpenAI routes, `authentication_error` on `/v1/messages`); any non-empty value is accepted; `/v1/messages` accepts `x-api-key` or `Authorization: Bearer`.
- [x] Admin validation: an unknown member, a negative latency, a status outside 400..599, `times` < 1 or an unknown fault kind gives 400 in the OpenAI envelope with `param` naming the member; the stored scenarios are unchanged.
- [x] Framework errors: `POST /v1/x` gives 404 in the OpenAI shape, `POST /v1/messages` with `{"a":` gives 400 `invalid_request_error` in the Anthropic shape; no body contains an exception message. The context holds exactly one `ErrorWebExceptionHandler`, `MockErrorWebExceptionHandler`.
- [x] BlockHound is installed in the `mock-provider` test JVM (a test proves a blocking call on a non-blocking thread fails) and every integration test passes under it.
- [x] Container: `MockProviderImageTest` builds the image from `mock-provider/Dockerfile` and the boot jar, starts it with Testcontainers, waits for `/actuator/health` 200 on the mapped port and gets a 200 chat completion; it lives in `src/integrationTest` (ADR-016, ADR-FND-03-012). `./gradlew :mock-provider:outgoingVariants` lists the consumable configuration `mockProviderBootJar`.
- [x] Deliberate violation: a `Thread.sleep(10)` inside a serving path makes detekt (`SleepInsteadOfDelay`) or a BlockHound-instrumented integration test fail.
- [x] `verify-gates` green (coverage: core ≥ 80%, overall ≥ 70%; `mock-provider` line coverage recorded in the Log)
- [x] Smoke: `gateway-smoke` cannot run the gateway scenarios (no `/v1` endpoint until GW-01). `gateway-tester` runs the standalone mock scenarios of section 8 over real sockets (bootRun on 8090, `java -jar`, `docker build` + `docker run`), report in `docs/knowledge/runs/`. Met by [runs/2026-11-03-smoke-mock-provider-and-errors](../knowledge/runs/2026-11-03-smoke-mock-provider-and-errors.md) (bootRun, 26 scenarios, pass with findings). Section 8 steps 6 and 7 were not run by hand: `MockProviderImageTest` builds the image from the same Dockerfile and boot jar and runs it (its entry point is `java -jar /app/mock-provider.jar`), so the jar starting on its own and the image serving health and a completion are proven on every `./gradlew build`. The smoke's other gaps (`hang`, `malformed`, `usage.omit`, a hold reaching `mock.max-hold`) are covered by `FaultInjectionIntegrationTest`, `OpenAiChatIntegrationTest` and `AnthropicMessagesIntegrationTest` over real Netty (Log, Closed).
- [x] Eval: n/a (no threshold, classifier or routing policy; the mock embeddings are not semantic and are never used by `ai-eval`).
- [x] Living docs updated (`docs-update`): the systems entry `docs/knowledge/systems/mock-provider.md`, the CLAUDE.md row and lines, glossary, diagram note, `gateway-smoke` skill, ADR-014 revision line and table, roadmap row (section 7, step 7).

## 4. Contract

The mock imitates providers, not the gateway. ADR-004 and ADR-015 govern the gateway's own surface; here the paths, bodies, headers and status codes are the providers' as documented on 2026-11-03, so adapters (GW-01, GW-03) are tested against the shapes they will meet. No `x-gateway-*` header exists on the mock. The admin API under `/__mock` is the mock's own.

One server, one port (8090), provider paths unchanged: an OpenAI client uses base URL `http://<host>:8090/v1`, an Anthropic client `http://<host>:8090`. Real provider paths make the base URL the only configuration difference between the mock and the provider.

### Routes

| Route id | Method and path | Provider shape |
|---|---|---|
| `openai.chat` | `POST /v1/chat/completions` | OpenAI |
| `openai.embeddings` | `POST /v1/embeddings` | OpenAI |
| `openai.models` | `GET /v1/models` | OpenAI |
| `anthropic.messages` | `POST /v1/messages` | Anthropic |
| `unknown` | any other path outside `/__mock` and `/actuator` | OpenAI, except Anthropic under `/v1/messages/**` |
| (admin, not counted) | `/__mock/**` | mock JSON; errors in the OpenAI envelope |
| (not counted) | `GET /actuator/health` | Boot actuator |

### Credentials

| Route | Accepted | Missing |
|---|---|---|
| OpenAI routes | `Authorization: Bearer <non-empty>` | 401 `{"error":{"message":"No API key was provided to mock-provider.","type":"invalid_request_error","param":null,"code":"invalid_api_key"}}` |
| `/v1/messages` | `x-api-key: <non-empty>` or `Authorization: Bearer <non-empty>` (the current Anthropic reference shows both) | 401 `{"type":"error","error":{"type":"authentication_error","message":"No API key was provided to mock-provider."},"request_id":"req_..."}` |

The value is never compared with anything. `anthropic-version` is recorded, not required (the reference marks it optional). `mock.auth.require-credential: false` disables the 401.

### Request handling common to all routes

- Request bodies are read as a JSON tree; unknown members are ignored and kept in the journal.
- Validation (400 in the route's provider shape, `invalid_request_error`, `param` set): `model` missing or not a string; OpenAI `messages` missing or empty; `n` present and not 1 (`code: "unsupported_value"`, `param: "n"`; a mock limit); Anthropic `max_tokens` missing or not an integer ≥ 0, `messages` missing or empty; embeddings `input` empty string, empty array, or an array element that is an empty string; `dimensions` < 1 or > `mock.embeddings.max-dimensions`; `encoding_format` not `float`/`base64`.
- Any model name is accepted. A model "not found" is produced only by a scenario (`error.status: 404`).

### OpenAI chat completion

Request members read: `model`, `messages[].role`, `messages[].content` (string, `null`, or an array whose `type: "text"` parts are joined with `\n`), `stream`, `stream_options.include_usage`, `max_completion_tokens`, `max_tokens` (deprecated by OpenAI; the smaller of the two when both are present), `n`. Everything else is ignored.

Non-stream response, `Content-Type: application/json`:

```json
{"id":"chatcmpl-mock-000000000001","object":"chat.completion","created":1793750400,"model":"mock-chat",
 "choices":[{"index":0,"message":{"role":"assistant","content":"Hi there","refusal":null,"annotations":[]},"logprobs":null,"finish_reason":"stop"}],
 "usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}},
 "service_tier":"default","system_fingerprint":"fp_mock"}
```

(Wrapped here; compact on the wire. Member order as shown.)

- `id`: `chatcmpl-mock-` + the arrival sequence, 12 digits zero-padded. `created`: the injected `Clock`, epoch seconds. `model`: the request's.
- `finish_reason`: `stop`, or `length` when the token cap cut the content.

Stream response, `Content-Type: text/event-stream;charset=UTF-8`, `Cache-Control: no-cache`, one flush per event:

```
data: {"id":"chatcmpl-mock-000000000001","object":"chat.completion.chunk","created":1793750400,"model":"mock-chat","system_fingerprint":"fp_mock","choices":[{"index":0,"delta":{"role":"assistant","content":""},"logprobs":null,"finish_reason":null}],"usage":null}

data: {...,"choices":[{"index":0,"delta":{"content":"Hi"},"logprobs":null,"finish_reason":null}],"usage":null}

data: {...,"choices":[{"index":0,"delta":{"content":" there"},"logprobs":null,"finish_reason":null}],"usage":null}

data: {...,"choices":[{"index":0,"delta":{},"logprobs":null,"finish_reason":"stop"}],"usage":null}

data: {...,"choices":[],"usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}}}

data: [DONE]

```

- The `usage` member (null, then the object in the last chunk) appears only with `stream_options.include_usage: true`, as OpenAI documents: "an additional chunk will be streamed before the `data: [DONE]` message", its `choices` "always an empty array", and all other chunks carry `usage` with a null value. Without it, no chunk has a `usage` member.
- Each content chunk carries one piece (section 5, Generation): its first token with the whitespace that preceded it.

### OpenAI embeddings

Request members read: `model`, `input` (string | string[] | int[] | int[][]), `dimensions` (default `mock.embeddings.default-dimensions` = 1536), `encoding_format` (`float` default; `base64`). openai-python sends `base64` whenever the caller does not set the format and decodes it as float32 (`np.frombuffer(..., dtype="float32")`), so `base64` is in scope.

```json
{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.0123,...]}],"model":"mock-embed","usage":{"prompt_tokens":2,"total_tokens":2}}
```

With `base64`, `embedding` is the standard Base64 of the vector's float32 values in little-endian order.

### OpenAI models

`GET /v1/models`: `{"object":"list","data":[{"id":"mock-chat","object":"model","created":<startup epoch s>,"owned_by":"mock-provider"}, ...]}`, one entry per `mock.models`.

### Anthropic messages

Request members read: `model`, `max_tokens`, `system` (string or array of `text` blocks), `messages[].role`, `messages[].content` (string or blocks; `text` blocks and the text inside `tool_result` blocks count), `stream`. Everything else is ignored.

Non-stream response, `Content-Type: application/json`:

```json
{"id":"msg_mock_000000000001","type":"message","role":"assistant","model":"mock-claude",
 "content":[{"type":"text","text":"Hi there"}],"stop_reason":"end_turn","stop_sequence":null,
 "usage":{"input_tokens":14,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"cache_creation":{"ephemeral_5m_input_tokens":0,"ephemeral_1h_input_tokens":0},"output_tokens":2,"service_tier":"standard"}}
```

`stop_reason`: `end_turn`, or `max_tokens` when the cap cut the content.

Stream response, `text/event-stream;charset=UTF-8`, one flush per event, the sequence the streaming guide documents:

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_mock_000000000001","type":"message","role":"assistant","model":"mock-claude","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":14,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":1}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: ping
data: {"type":"ping"}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}

event: message_stop
data: {"type":"message_stop"}

```

- `message_delta.usage` carries only `output_tokens` (cumulative), like the documented example, so an adapter must take input and cache counts from `message_start`. The reference allows cumulative `input_tokens` and cache fields there too; the mock deliberately sends the narrower form.
- JSON is compact. The providers' own examples differ in whitespace; SSE and JSON parsers ignore it. "Framing exactly as providers" means the SSE field lines, blank-line separators, event names, event order and the `[DONE]` sentinel.

### Response headers on provider routes

| Header | OpenAI routes | `/v1/messages` |
|---|---|---|
| request ID | `x-request-id: req_<32 hex of the arrival sequence>` | `request-id: req_<same>`; also the body's `request_id` on errors |
| others | those an error step lists in `headers`, plus `retry-after` / `retry-after-ms` from `retryAfterSeconds` / `retryAfterMs` | same |

The mock's `x-request-id` is the provider's request ID. The gateway must not pass it through as its own (ADR-015); GW-01 may log it.

### Error bodies and defaults

OpenAI shape: `{"error":{"message":<string>,"type":<string>,"param":<string|null>,"code":<string|null>}}`. Anthropic shape: `{"type":"error","error":{"type":<string>,"message":<string>},"request_id":"req_..."}`. Defaults per status when a scenario sets only `status` (any of `type`, `code`, `message` can be overridden):

| Status | OpenAI `type` / `code` | Anthropic `error.type` | Source |
|---|---|---|---|
| 400 | `invalid_request_error` / `null` | `invalid_request_error` | both error docs |
| 401 | `invalid_request_error` / `invalid_api_key` | `authentication_error` | FND-04 section 4 (OpenAI, public report); Anthropic errors doc |
| 402 | `invalid_request_error` / `null` | `billing_error` | Anthropic errors doc |
| 403 | `invalid_request_error` / `null` | `permission_error` | Anthropic errors doc; OpenAI documents no 403 `type` |
| 404 | `invalid_request_error` / `model_not_found` | `not_found_error` | FND-04 section 4; Anthropic errors doc |
| 409 | `invalid_request_error` / `null` | `conflict_error` | Anthropic errors doc |
| 413 | `invalid_request_error` / `null` | `request_too_large` | Anthropic errors doc |
| 429 | `requests` / `rate_limit_exceeded` | `rate_limit_error` | FND-04 section 4 (observed OpenAI bodies); Anthropic errors doc |
| 500 | `server_error` / `null` | `api_error` | both |
| 503 | `service_unavailable_error` / `server_is_overloaded` | `api_error` | OpenAI error-codes guide |
| 504 | `server_error` / `null` | `timeout_error` | Anthropic errors doc |
| 529 | `server_error` / `null` | `overloaded_error` | Anthropic errors doc (529 is not an OpenAI status; allowed for tests) |
| other 4xx / 5xx | `invalid_request_error` / `server_error`, `null` | `invalid_request_error` / `api_error` | Anthropic: "may also be used for other 4XX" |

Default messages are fixed strings written for the mock ("mock-provider injected HTTP 429."), never copied from a provider. An OpenAI quota error is a 429 with `type`/`code` overridden to `insufficient_quota`; an Anthropic spend-cap 429 is a 429 without `retryAfterSeconds`.

In-band stream errors (`fault.kind: stream_error`): OpenAI `data: {"error":{...four members...}}\n\n`, then the stream ends without `[DONE]` (openai-python raises `APIError` on a data object whose `error` member is set; what OpenAI sends after it is not documented). Anthropic `event: error\ndata: {"type":"error","error":{"type":"overloaded_error","message":"..."}}\n\n`, then the stream ends. The error's `type`/`message` come from the step's `error` block (default status 529 for Anthropic, 500 for OpenAI).

### Admin API (`/__mock`)

All bodies `application/json`; errors in the OpenAI envelope with `param` naming the offending member. Not counted, not journaled.

| Method and path | Body / query | Response |
|---|---|---|
| `PUT /__mock/scenarios/{model}` | `Scenario` (below); `{model}` = `*` for the default | 204; replaces any scenario for that model and restarts its step cursor |
| `GET /__mock/scenarios` | – | 200 `{"data":[{"model","steps","otherwise","stepsConsumed"}]}` |
| `DELETE /__mock/scenarios/{model}` | – | 204 (also when absent) |
| `DELETE /__mock/scenarios` | – | 204, all removed |
| `GET /__mock/requests` | `route`, `model`, `since` (arrival seq, exclusive), `header=<name>:<value>` (repeatable, all must match, name case-insensitive), `limit` (default 100, max 1000) | 200 `{"data":[JournalEntry...]}` oldest first |
| `GET /__mock/stats` | – | 200 `{"lastSeq":42,"inFlight":0,"counts":[{"route":"openai.chat","model":"mock-chat","requests":40,"outcomes":{"completed":38,"error_injected":2}}],"journal":{"capacity":1000,"size":40,"dropped":0}}` |
| `POST /__mock/reset` | – | 204; removes scenarios, counts and journal; `lastSeq` is not rewound |

`Scenario`:

```json
{
  "steps": [
    {"times": 2, "error": {"status": 503}},
    {"latency": {"ttftMs": 800}}
  ],
  "otherwise": {"latency": {"ttftMs": 20, "perTokenMs": 5, "jitterMs": 0}}
}
```

`Behaviour` (a step adds `times`, default 1, minimum 1):

| Member | Fields | Meaning |
|---|---|---|
| `latency` | `ttftMs`, `perTokenMs`, `jitterMs` (integers ≥ 0, each ≤ `mock.max-hold`) | Wait `ttftMs` + U[0, `jitterMs`] before the first byte (stream: before the first event; non-stream: then also `perTokenMs` × completion tokens before the body; errors: `ttftMs` + jitter before the error). Each later stream event waits `perTokenMs` + U[0, `jitterMs`]. |
| `error` | `status` (400..599), `type`, `code`, `message`, `param`, `retryAfterSeconds`, `retryAfterMs`, `headers` (string map) | Respond with that error in the route's shape instead of content. |
| `fault` | `kind` (`hang`, `stall`, `disconnect`, `stream_error`, `malformed`), `afterPieces` (integer ≥ 0, default 0) | Section 5, Faults. `stream_error` uses the step's `error` block for the event. |
| `content` | `mode` (`lorem`, `echo`, `fixed`), `text` (with `fixed`), `completionTokens` (1..4096) | Section 5, Generation. |
| `usage` | `omit` (bool), `cachedTokens`, `cacheCreationTokens`, `promptTokens`, `completionTokens` (integers ≥ 0) | Section 5, Usage. |

Unknown members are rejected (a typo in a test fails loudly). `error` and `fault` together are allowed only for `stream_error`.

**Resolution per request**, given the request's model `m`:
1. The scenario for `m`, else the `*` scenario, else none.
2. Its next unconsumed step (the cursor advances atomically, one step per request, in arrival order), else its `otherwise`.
3. Each member left unset inherits, in order, from that scenario's `otherwise`, the `*` scenario's `otherwise`, then the configured defaults (`mock.defaults.*`).

`JournalEntry`:

```json
{"seq":7,"route":"openai.chat","method":"POST","path":"/v1/chat/completions","model":"mock-chat",
 "stream":true,"includeUsage":true,"receivedAt":"2026-11-03T12:00:00.123Z","durationMs":412,
 "headers":{"content-type":["application/json"],"authorization":["[redacted]"],"x-client-request-id":["c-1"]},
 "credential":{"scheme":"bearer","fingerprint":"9f2c1a7b"},
 "body":"{...}","bodyTruncated":false,
 "scenario":{"model":"mock-chat","step":0},
 "outcome":{"kind":"completed","status":200,"piecesSent":17,"promptTokens":14,"completionTokens":17}}
```

- `outcome.kind`: `completed`, `error_injected`, `rejected` (validation, credential or framework error), `client_cancelled`, `aborted` (`disconnect`), `stream_error`, `malformed`, `timed_out` (a `hang` that reached `mock.max-hold`).
- `credential.scheme`: `bearer`, `x-api-key` or `null`; `fingerprint`: the first 8 hex digits of SHA-256 of the credential value, or `null`. The value itself is never stored.
- `scenario.step`: the zero-based step index, or `null` when `otherwise` or defaults applied.
- `body`: the raw request body as text, cut at `mock.journal.max-body-bytes` (then `bodyTruncated: true`); `null` when `mock.journal.capture-bodies` is false or no body was read.

**Asserting that no call reached the mock** (CACHE-01 hits, GRD-01 blocks, smoke): read `GET /__mock/stats` `lastSeq` before the gateway call and again after; equal means no request of any kind arrived, including misrouted ones. With concurrent traffic on the same mock, correlate instead: `GET /__mock/requests?since=<lastSeq>&header=<name>:<value>` with the header the gateway forwards upstream (GW-01 decides which; FND-04 suggests sending the gateway request ID as `X-Client-Request-Id`), which must be empty.

## 5. Design

### Modules and ports

One new module, role `app` (ADR-FND-03-003), `dependsOn` empty: the mock imports nothing of ours, so a bug in a shared type cannot hide in both the gateway and its test double. No port: the mock has no capability with more than one implementation. Pure logic lives in packages that import neither Spring, Reactor nor Jackson, and is unit-tested without a Spring context; HTTP, JSON and wiring live in the others.

| Package under `.mockprovider` | Pure | Contents |
|---|---|---|
| (root) | no | `MockProviderApplication` (`@SpringBootApplication`, `fun main(vararg args: String)`) |
| `config` | no | `MockProperties` (`@ConfigurationProperties("mock")` data class, `init { require(...) }` checks), `MockConfiguration` (`@Configuration`: `Clock.systemUTC()`, `ScenarioStore`, `RequestJournal`, `TextGenerator`, `Embedder`, the router bean) |
| `behaviour` | yes | `Behaviour`, `Latency`, `InjectedError`, `Fault`, `FaultKind`, `ContentSpec`, `ContentMode`, `UsageSpec`, `Scenario`, `ScenarioStore`, `ResolvedBehaviour` |
| `generation` | yes | `Fnv1a`, `Tokenizer`, `Conversation` (turns + canonical text + last user text), `TextGenerator`, `CompletionPlanner` (`CompletionPlan`: pieces, finish, usage), `Embedder` |
| `journal` | yes | `RequestJournal`, `JournalEntry`, `Outcome`, `HeaderRedaction`, `CredentialFingerprint` (uses `java.security.MessageDigest`, warmed at construction) |
| `provider` | yes | `ProviderApi` (`OPENAI`, `ANTHROPIC`), `ProviderErrors` (section 4 defaults table: `fun defaults(api, status): ErrorDefaults`) |
| `sse` | yes | `SseFrames`: `openAiData(json: String): String`, `openAiDone(): String`, `anthropicEvent(type: String, json: String): String` |
| `web` | no | `ArrivalWebFilter` (`CoWebFilter`, first: arrival sequence, `inFlight`, epoch, route classification, journals requests no handler journaled), `MockRoutes` (`coRouter`), `Serving` (resolve behaviour, apply latency, error, fault; record the outcome), `SseBodyInserter`, `Credentials` |
| `openai` | no | `OpenAiRequests` (JSON tree to `Conversation`, caps, flags), `OpenAiRenderer` (JSON tree for completion, chunk, embeddings, models, error), `OpenAiHandlers` |
| `anthropic` | no | `AnthropicRequests`, `AnthropicRenderer`, `AnthropicHandler` |
| `admin` | no | `AdminHandlers`, `ScenarioJson` (tree to `Scenario` with strict validation, and back) |
| `error` | no | `MockErrorWebExceptionHandler`, `ErrorHandlingConfiguration`, `MockConnectionAbort` |

Key signatures (pure packages):

```kotlin
// behaviour
class ScenarioStore(defaults: Behaviour) {
    fun put(model: String, scenario: Scenario)            // replaces, cursor restarts at 0
    fun remove(model: String)
    fun clear()
    fun resolve(model: String?): ResolvedBehaviour       // atomic step take, then inheritance (section 4)
    fun snapshot(): List<ScenarioState>
}

// generation
object Tokenizer {
    fun count(text: String): Int                          // matches of [\p{L}\p{N}]+|[^\s\p{L}\p{N}]
    fun pieces(text: String): List<String>                // each token with the whitespace before it; concatenation == text
}
class TextGenerator(vocabulary: List<String>) {
    fun lorem(seed: Long, tokens: Int): String
}
class CompletionPlanner(textGenerator: TextGenerator, defaultTokens: Int) {
    fun plan(conversation: Conversation, maxTokens: Int?, content: ContentSpec, usage: UsageSpec): CompletionPlan
}
class Embedder {
    fun embed(input: EmbeddingInput, dimensions: Int): FloatArray   // L2-normalised feature hashing
}

// journal
class RequestJournal(capacity: Int, clock: Clock) {
    fun arrive(): Arrival                                  // seq, epoch, receivedAt; inFlight++
    fun complete(arrival: Arrival, entry: JournalEntry)    // dropped silently if arrival.epoch != current epoch
    fun count(route: String, model: String?, outcome: Outcome.Kind)
    fun query(filter: JournalFilter): List<JournalEntry>
    fun stats(): JournalStats
    fun reset()                                            // epoch++, counts and ring cleared, seq kept
}
```

Libraries: Spring Boot WebFlux, actuator, `kotlinx-coroutines-reactor` (all from `llmgw.spring-app`); Jackson 3 through Boot's configured `JsonMapper` bean, tree model only (ADR-FND-03-010), so no Jackson Kotlin module is added. Tests add `blockhound-junit-platform`; the `integrationTest` suite adds Testcontainers (`testcontainers` core, version from the Testcontainers BOM; catalog alias `testcontainers-core`, because `testcontainers` would be a prefix of `testcontainers-bom`) (ADR-FND-03-012).

### Generation

- **Conversation text.** OpenAI: each message's text (string content; or `text` parts joined by `\n`; `null` is empty). Anthropic: `system` as a first turn with role `system`, then each message's `text` blocks and the text inside `tool_result` blocks. The canonical string is `model`, then for each turn `\u0000 role \u0000 text`. `stream`, sampling parameters and every other member are excluded, so the stream and non-stream forms of a request produce the same text, and the same conversation produces the same text on either API when the model names match.
- **Seed.** FNV-1a 64-bit (draft-eastlake-fnv) over the canonical string's UTF-8 bytes. Pure Kotlin, no JCA call on the event loop.
- **Modes.** `lorem` (default): `count` words drawn from a fixed 64-word lowercase English vocabulary (written for this project, a constant in `TextGenerator`) with `SplittableRandom(seed)`, joined by single spaces. `echo`: the text of the last `user` turn. `fixed`: the scenario's `text`.
- **Length.** `lorem` produces `content.completionTokens` (default `mock.defaults.completion-tokens` = 16) tokens. Every mode is then cut to the request's cap (`max_completion_tokens`/`max_tokens`, Anthropic `max_tokens`) in pieces; a cut sets `length` / `max_tokens`. Anthropic `max_tokens: 0` gives empty content, `stop_reason: "max_tokens"`.
- **Pieces.** `Tokenizer.pieces` splits the final text into pieces, each one token with the whitespace before it. A stream sends one piece per content event; `completion_tokens` equals the piece count. Corrected 2026-11-04 (GW-03): `CompletionPlan` carries a list of `PlanPart`s, thinking, text and tool calls; this line said "today only text", and until GW-03 the plan held only `pieces: List<String>` (ADR-GW-03-019).
- **Tests must not assert exact `lorem` words** (the vocabulary may be edited); golden files use `fixed` content.

### Usage

Approximation, documented as not a real tokenizer (ADR-FND-03-005):
- `tokens(text)` = `Tokenizer.count(text)`.
- Chat prompt tokens `P` = Σ over turns (3 + tokens(text)) + 3, the per-message overhead and reply priming of OpenAI's token-counting cookbook (`tokens_per_message = 3`, "every reply is primed with ... +3"). Applied to both APIs.
- Completion tokens `C` = number of pieces.
- Embeddings: Σ tokens(input string), or the array length for token arrays.
- Scenario `usage.promptTokens`/`completionTokens` replace `P`/`C` in the report only (the content is unchanged), for RL-02 reconciliation tests. `cachedTokens` `K` and `cacheCreationTokens` `W` are clamped: `K' = min(K, P)`, `W' = min(W, P − K')`.
- OpenAI: `prompt_tokens = P`, `prompt_tokens_details.cached_tokens = K'` (a subset of `P`), `completion_tokens = C`, `total_tokens = P + C`. `W` is not reported on OpenAI.
- Anthropic: `input_tokens = P − K' − W'`, `cache_read_input_tokens = K'`, `cache_creation_input_tokens = W'`, `cache_creation.ephemeral_5m_input_tokens = W'`, `output_tokens = C` (the reference: total input is the sum of the three input fields). `message_start` reports `output_tokens: 1`.
- `usage.omit`: OpenAI non-stream has no `usage` member and the include-usage chunk is not sent (chunks keep `"usage":null` when `include_usage` was requested); Anthropic has no `usage` member in the non-stream body, `message_start.message` or `message_delta`. This drives the gateway's `estimated` path (CLAUDE.md, Provider adapters).

### Embeddings

Feature hashing (Weinberger et al., 2009): lowercase the input; for each `[\p{L}\p{N}]+` token (or `t<id>` for each id of a token array) compute FNV-1a 64 `h`; add `+1` or `−1` (bit 0 of `h`) at index `(h ushr 1) mod dimensions`; L2-normalise. An input with no such token hashes the whole string as one token. Identical inputs give identical vectors; inputs sharing words have positive cosine similarity; disjoint inputs are near-orthogonal. It is deterministic and cheap, enough for CACHE-02's mechanics (identical and reworded questions), and explicitly not semantic.

### Faults

| `kind` | Non-stream | Stream |
|---|---|---|
| `hang` | Waits (after latency) until the client goes away or `mock.max-hold`; then 504 in the provider shape, outcome `timed_out`. | Same, before any byte. |
| `stall` | Same as `hang`. | Sends the first `afterPieces` events (role chunk / `message_start`..`ping` count as the opening, not pieces), then waits as `hang`; at `max-hold` the stream is aborted. |
| `disconnect` | Commits status 200 and headers, sends the first half of the body bytes, then aborts the connection. | Sends the opening and `afterPieces` pieces, then aborts. |
| `stream_error` | n/a: treated as `error` (an HTTP error response). | Sends the opening and `afterPieces` pieces, then the in-band error event (section 4), then ends normally. |
| `malformed` | 200 `application/json` with the body `{"id": "chatcmpl-mock` (not JSON). | Opening and `afterPieces` pieces, then `data: {not json` (OpenAI) or `event: content_block_delta\ndata: {not json` (Anthropic) as one event, then the normal ending. |

Aborting: the body publisher signals `MockConnectionAbort` after the response is committed; the mapper sees a committed response, logs at DEBUG and returns the error, and Reactor Netty closes the connection without the terminating chunk, so the client sees a premature close (the same path FND-04 describes for the gateway's committed errors).

### Data

n/a for Flyway, Redis and the broker: the mock has no store. All state is process memory and is lost on restart, by design.

| Fact | Source of truth | Structure | Concurrency |
|---|---|---|---|
| Scenarios | `ScenarioStore` | `ConcurrentHashMap<String, ScenarioState>`; `ScenarioState` = immutable `Scenario` with steps expanded by `times`, plus an `AtomicInteger` cursor | `resolve` does one `getAndIncrement`; index < size takes that step, otherwise `otherwise`. No lock: exactly `times` requests get a step under any concurrency. `put` swaps the whole state. |
| Arrival sequence, `inFlight`, epoch | `RequestJournal` | `AtomicLong`, `AtomicInteger`, `AtomicInteger` | `seq` is monotonic for the process life; `reset` increments the epoch and never rewinds `seq`. |
| Counts | `RequestJournal` | `ConcurrentHashMap<CountKey(route, model, outcome), LongAdder>` | Lock-free increments. |
| Journal | `RequestJournal` | `AtomicReference<Ring>`; `Ring` = `AtomicReferenceArray<JournalEntry?>(capacity)` plus an `AtomicLong` write counter; slot = write counter mod capacity | Entries are immutable and written once, at completion; `dropped` = writes − capacity when positive. Reads copy the non-null slots and sort by `seq`. `reset` swaps in an empty ring; a completion from an older epoch is discarded. |

Bounds: journal `capacity` (default 1000) × `max-body-bytes` (default 16 KiB) caps body memory near 16 MiB. Load runs (OPS-01) set `mock.journal.capture-bodies: false`.

### Flow

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (adapter, gateway, test)
    participant F as ArrivalWebFilter
    participant H as Handler (openai / anthropic)
    participant S as ScenarioStore
    participant P as CompletionPlanner
    participant J as RequestJournal

    C->>F: POST /v1/chat/completions
    F->>J: arrive() -> seq, epoch (inFlight++)
    F->>H: chain.filter (route attribute set)
    H->>H: credential check, parse JSON tree, validate
    H->>S: resolve(model) -> step or otherwise, inherited fields
    H->>P: plan(conversation, cap, content, usage)
    H->>H: delay(ttft + jitter)
    alt error
        H-->>C: provider-shaped error + headers
    else stream
        loop each event
            H-->>C: SSE frame, flushed (delay perTokenMs + jitter between)
        end
    else non-stream
        H-->>C: JSON body (after perTokenMs x C)
    end
    H->>J: complete(entry with outcome, piecesSent, usage)
    Note over C,H: client disconnect cancels the coroutine; onCompletion records client_cancelled
```

No `docs/diagrams/` sequence is added; `container-overview.md` already shows `mock-provider` and gets one note line (step 7).

### Failure model

The mock is a dependency of tests, not of production. It has no dependency of its own (no provider, Redis, Postgres or broker).

| Event | Behaviour | Why |
|---|---|---|
| Client disconnects mid-request | The handler coroutine or the stream `Flow` is cancelled by WebFlux; `onCompletion` records `client_cancelled` with `piecesSent`; `CancellationException` is rethrown, never swallowed. | GW-02 asserts upstream cancellation through this outcome. |
| Latency | `kotlinx.coroutines.delay` only; never `Thread.sleep` (detekt `SleepInsteadOfDelay`, BlockHound). | A blocked event loop would serialize OPS-01's load and distort the overhead number. |
| `hang` / `stall` longer than the client waits | Held until the client goes away, at most `mock.max-hold` (default 300 s), then 504 or an abort. | A forgotten hanging request must not leak forever in a long smoke or load session. |
| Concurrent admin change during a request | The request keeps the behaviour it resolved at arrival. | One atomic resolve per request; no torn reads. |
| `reset` during in-flight requests | Their completions are discarded (epoch check); their arrivals advanced `lastSeq` before the reset. | After a reset, nothing from before it appears. |
| Journal full | Oldest entries overwritten; `journal.dropped` counts them. | Bounded memory under load. |
| Body larger than `spring.http.codecs.max-in-memory-size` (10 MB) | 413 in the provider shape via the mapper. | Same funnel as every framework error. |
| Unknown path, bad JSON, wrong method or media type | Provider-shaped 404/400/405/406/415 from `MockErrorWebExceptionHandler`; journaled as `rejected`. | A misrouted gateway call must be visible in the counter and log. |
| Bug in the mock | 500 in the provider shape, ERROR log with stack trace, no exception message in the body. | Pattern `one-error-funnel`. |
| Shutdown | `server.shutdown: immediate`. | Graceful shutdown (Boot's default) would wait out hanging requests. |
| Retries, idempotency keys, timeouts on outbound calls | n/a: the mock makes no outbound call. | |

### Observability

- **Logs:** one INFO line at startup with port, models and defaults; DEBUG per completed request (seq, route, model, status, outcome kind, duration ms); ERROR only for mock bugs. Never logged: bodies, header values, credential values or fingerprints. `org.springframework.web.server.adapter.HttpWebHandlerAdapter` is set to `off`, as in `gateway-app`, because every intentional abort would otherwise produce an ERROR line with the raw query string ([bug](../knowledge/bugs/committed-response-error-logged-twice-with-query-string.md)).
- **The journal is the observability surface** for tests: it keeps bodies in memory and returns them over `/__mock/requests`. The mock only ever receives synthetic test data; credentials are redacted at capture (ADR-FND-03-008). It is never pointed at real traffic.
- `traceparent` and `tracestate` are kept in the journal headers, so OBS-01 can assert trace propagation to the provider.
- **Spans and metrics:** none; actuator `health` only.

### Configuration

`mock-provider/src/main/resources/application.yaml`, all bound to `MockProperties` and checked at startup.

| Property | Default | Unit / range |
|---|---|---|
| `server.port` | 8090 | CLAUDE.md local ports |
| `server.shutdown` | `immediate` | |
| `spring.application.name` | `mock-provider` | |
| `spring.web.resources.add-mappings` | `false` | no static files (as FND-04) |
| `spring.webflux.problemdetails.enabled` | `false` | one mapper (ADR-015) |
| `spring.http.codecs.max-in-memory-size` | `10MB` | request body limit |
| `logging.level.org.springframework.web.server.adapter.HttpWebHandlerAdapter` | `off` | see Observability |
| `management.endpoints.web.exposure.include` | `health` | |
| `mock.models` | `[mock-chat, mock-chat-mini, mock-claude, mock-embed]` | listed by `/v1/models` only |
| `mock.defaults.latency.ttft` | `0ms` | `Duration`, 0..`max-hold` |
| `mock.defaults.latency.per-token` | `0ms` | `Duration` |
| `mock.defaults.latency.jitter` | `0ms` | `Duration` |
| `mock.defaults.completion-tokens` | 16 | 1..4096 tokens |
| `mock.defaults.content-mode` | `lorem` | `lorem`, `echo` |
| `mock.embeddings.default-dimensions` | 1536 | 1..`max-dimensions` |
| `mock.embeddings.max-dimensions` | 3072 | |
| `mock.auth.require-credential` | `true` | |
| `mock.journal.capacity` | 1000 | entries, 1..100000 |
| `mock.journal.max-body-bytes` | 16384 | bytes |
| `mock.journal.capture-bodies` | `true` | |
| `mock.journal.read-wait` | `250ms` | longest wait of `/__mock/stats` and `/__mock/requests` for requests in flight (2026-11-03) |
| `mock.max-hold` | `300s` | `Duration` |

Every property can be set from the environment with Boot's relaxed binding (`MOCK_DEFAULTS_LATENCY_TTFT=50ms`), which is how a smoke or k6 run fixes the upstream latency without admin calls.

## 6. Reuse

- ADR-004 and ADR-015 for what the mock must not be confused with (the gateway's contract); ADR-005, ADR-006 (WebFlux + coroutines, non-blocking); ADR-013 (base package); ADR-014 (module creation checklist and role change, revised by ADR-FND-03-003).
- FND-01: `llmgw.spring-app` convention, the module table and ARCH-1 to ARCH-10, Kover merge, the BlockHound and Testcontainers catalog versions; U8 (BlockHound flag on JDK 25) is first settled here.
- FND-04: the reserved OpenAI error rows (section 4 of that spec) for the mock's OpenAI defaults; the committed-response abort behaviour; `spring.web.resources.add-mappings` and problem-details settings.
- Patterns: [`one-error-funnel`](../knowledge/patterns/one-error-funnel.md) (the mock's mapper in `.mockprovider.error`, provider-shaped instead of the gateway envelope; injected errors are data, not exceptions); [`architecture-rule-with-violating-fixture`](../knowledge/patterns/architecture-rule-with-violating-fixture.md) (no new rule; the role change keeps every rule's fixture valid).
- Gotchas and bugs: [`committed-response-error-logged-twice-with-query-string`](../knowledge/bugs/committed-response-error-logged-twice-with-query-string.md) (logger off); [`spring-disconnected-client-helper-matches-phrases-in-cause-chain`](../knowledge/gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md) (the mock's mapper decides DEBUG vs ERROR by `MockConnectionAbort` type, and may use the helper only for the log level); [`detekt-forbiddenvoid-flags-mono-void`](../knowledge/gotchas/detekt-forbiddenvoid-flags-mono-void.md) (`SseBodyInserter` returns `Mono<Void>`; already configured); [`spring-httpstatus-deprecated-aliases-never-resolved`](../knowledge/gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md) (map statuses by integer); [`konsist-paths-and-module-names-use-platform-separator`](../knowledge/gotchas/konsist-paths-and-module-names-use-platform-separator.md) (module name `mock-provider` compared through `ModuleFile`).
- Prior art consulted, not reused: WireMock (journal at `/__admin/requests`, fault types, chunked dribble delay), ADR-FND-03-001.

## 7. Execution plan

Steps of about half a day each; tick as they land. Pure packages are test-first.

- [x] **Step 1: module skeleton and rules.**
  - `settings.gradle.kts` includes `mock-provider`; `mock-provider/build.gradle.kts` applies `llmgw.spring-app`; `bootJar { archiveFileName = "mock-provider.jar" }`; consumable configuration `mockProviderBootJar` carrying the boot jar (Gradle "share outputs" guide, variant-aware); test dependencies `blockhound-junit-platform`, `platform(testcontainers-bom)`, `testcontainers`; every `Test` task gets `-XX:+AllowRedefinitionToAddDeleteMethods` (BlockHound on JDK 25, FND-01 U8); the `integrationTest` task (ADR-016, ADR-FND-03-012; the plan first said `tasks.test`) declares the boot jar as an input and passes its path through a `CommandLineArgumentProvider` system property `mockProvider.jar` (configuration-cache safe). The Testcontainers dependencies are `integrationTestImplementation`.
  - Catalog alias `testcontainers = { module = "org.testcontainers:testcontainers" }` (version from the BOM; confirm the 2.x core coordinate). As built: alias `testcontainers-core` (the coordinate is confirmed), and `blockhound` corrected to `1.0.18.RELEASE`.
  - Root `build.gradle.kts`: `kover(project(":mock-provider"))` and the `MockProviderApplicationKt` exclusion.
  - `Modules.kt`: `Module("mock-provider", "$BASE_PACKAGE.mockprovider", Role.APP)`.
  - `MockProviderApplication`, `application.yaml`, `MockProperties` (with `require` checks), `MockApplicationTest` (context, health, BlockHound active).
  - `mock-provider/Dockerfile`: `FROM eclipse-temurin:25-jre-noble`, a non-root user, `COPY mock-provider.jar /app/`, `EXPOSE 8090`, `ENTRYPOINT ["java","-jar","/app/mock-provider.jar"]`.
  - `./gradlew build`; ARCH green; deliberate ARCH-9 violation.
- [x] **Step 2: generation (TDD).** `Fnv1a` (published test vectors), `Tokenizer`, `Conversation`, `TextGenerator`, `CompletionPlanner`, `Embedder`, with the unit tests of section 8.
- [x] **Step 3: behaviour, journal, errors, frames (TDD).** `Behaviour` types, `ScenarioStore` (including the concurrency test), `RequestJournal`, `HeaderRedaction`, `CredentialFingerprint`, `ProviderErrors`, `SseFrames`.
- [x] **Step 4: OpenAI routes.** `ArrivalWebFilter`, `Credentials`, `Serving`, `SseBodyInserter`, `MockRoutes`, `OpenAiRequests`, `OpenAiRenderer`, `OpenAiHandlers`; renderer golden tests; `OpenAiChatIntegrationTest`, `OpenAiStreamIntegrationTest`, `OpenAiEmbeddingsAndModelsIntegrationTest`.
- [x] **Step 5: Anthropic route and the mapper.** `AnthropicRequests`, `AnthropicRenderer`, `AnthropicHandler`; `MockErrorWebExceptionHandler`, `MockConnectionAbort`, `ErrorHandlingConfiguration`; golden tests; `AnthropicMessagesIntegrationTest`, `MockErrorEnvelopeIntegrationTest`.
- [x] **Step 6: control surface and faults.** `ScenarioJson`, `AdminHandlers`; latency, error and fault paths in `Serving`; `AdminApiIntegrationTest`, `FaultInjectionIntegrationTest`, `LatencyIntegrationTest`, `JournalIntegrationTest`.
- [x] **Step 7: container, standalone checks, docs, gates.**
  - `MockProviderImageTest`; `./gradlew :mock-provider:outgoingVariants`; the manual checks of section 3 (bootRun, shutdown, deliberate `Thread.sleep`).
  - Docs, in `docs-update` order (the implementer applies them; this plan does not):
    - Create `docs/knowledge/systems/mock-provider.md` from `systems/_template.md`: summary; contract rules 1 to 6 below; how it works (packages, resolution, journal, faults); configuration; decisions (this spec's ADRs); gotchas found. Index it in `docs/knowledge/INDEX.md` (Systems). Contract rules:
      1. The same request (model and conversation) always gets the same content and the same usage; stream and non-stream agree (`TextGeneratorTest`, `OpenAiStreamIntegrationTest`).
      2. A credential value is never stored, logged or returned; only scheme and an 8-hex SHA-256 fingerprint (`HeaderRedactionTest`, `JournalIntegrationTest`).
      3. A scenario step is consumed by exactly one request, in arrival order (`ScenarioStoreTest` concurrency case).
      4. Every non-admin request advances `lastSeq` and produces exactly one journal entry; after `reset` nothing from before it appears (`JournalIntegrationTest`).
      5. Streams are written one flushed event at a time in the providers' framing (golden files, `LatencyIntegrationTest`).
      6. The mock imports nothing of ours (`dependsOn` empty, ARCH-9).
    - CLAUDE.md, Systems table: row `| Mock provider | [mock-provider](docs/knowledge/systems/mock-provider.md) | The same request always gets the same content and usage; a credential value is never stored, logged or returned; a scenario step is consumed by exactly one request. |`
    - CLAUDE.md, Architecture: "Built today: ..." adds "`mock-provider` (FND-03, a Spring Boot app that imports nothing of ours)"; the graph block gains the line `mock-provider                                        (provider fake on :8090; imports nothing of ours; FND-03)`.
    - CLAUDE.md, Testing: the line "Integration tests use Testcontainers (...) and WireMock or `mock-provider`." gains: "`mock-provider` runs as a container built from its boot jar and `mock-provider/Dockerfile` (consume the `mockProviderBootJar` configuration); `./gradlew test` needs Docker from FND-03 on."
    - `docs/GLOSSARY.md` rows: Mock provider (`MockProviderApplication`: the deterministic provider fake on port 8090, never a real provider); Scenario (`Scenario`, `Behaviour`: per-model ordered steps plus a fallback behaviour, set over `/__mock/scenarios`); Request journal (`RequestJournal`: the mock's bounded, redacted log of received requests and its counters).
    - `docs/diagrams/container-overview.md`: one bullet "`mock-provider` (`:8090`) answers the OpenAI and Anthropic paths and exposes `/__mock` for scenarios, counters and the request journal (FND-03)", `Updated` date.
    - `.claude/skills/gateway-smoke/SKILL.md`: the intro sentence becomes "Until FND-02 (stack) lands, say the run is not possible and why."; step 2 adds "Start `mock-provider` too if the stack does not run it (`./gradlew :mock-provider:bootRun` in the background, health on `:8090`), with the latency the run needs (`MOCK_DEFAULTS_LATENCY_TTFT`, `MOCK_DEFAULTS_LATENCY_PER_TOKEN`), and `POST :8090/__mock/reset` before the scenarios"; step 4 Cache line ends "no call reaching the mock (`/__mock/stats` `lastSeq` unchanged)"; Guardrail line "(`/__mock/requests?since=<seq>` body)"; Fallback line "the mock set to fail the primary model (`PUT /__mock/scenarios/<model>` with an error step)"; Stream line adds "with `perTokenMs` set, chunks arrive spread over the stream, not at once"; step 5 Load adds "`MOCK_JOURNAL_CAPTURE_BODIES=false`".
    - `docs/DECISIONS.md`, ADR-014: the dated revision line and table edit proposed in the hand-back (the role of `mock-provider` becomes `app`).
    - `docs/roadmap.md` FND-03 row: status, spec link, systems link.
  - `verify-gates`; Log entry with the coverage numbers and every unverified item's outcome.

**Files created:**

```
mock-provider/build.gradle.kts
mock-provider/Dockerfile
mock-provider/src/main/resources/application.yaml
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/MockProviderApplication.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/MockProperties.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/config/MockConfiguration.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/Behaviour.kt        (Behaviour, Latency, InjectedError, Fault, FaultKind, ContentSpec, ContentMode, UsageSpec, ResolvedBehaviour)
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/Scenario.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStore.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/Fnv1a.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/Tokenizer.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/Conversation.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/TextGenerator.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/CompletionPlanner.kt   (CompletionPlan, PlannedUsage)
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/Embedder.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/RequestJournal.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/JournalEntry.kt          (JournalEntry, Outcome, JournalFilter, JournalStats)
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/HeaderRedaction.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/CredentialFingerprint.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/provider/ProviderErrors.kt       (ProviderApi, ErrorDefaults)
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/sse/SseFrames.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/ArrivalWebFilter.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Credentials.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/MockRoutes.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/SseBodyInserter.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiRequests.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiRenderer.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiHandlers.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicRequests.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicRenderer.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicHandler.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/AdminHandlers.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockConnectionAbort.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorWebExceptionHandler.kt
mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/ErrorHandlingConfiguration.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/MockApplicationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/Fnv1aTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/TokenizerTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/ConversationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/TextGeneratorTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/CompletionPlannerTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/generation/EmbedderTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/behaviour/ScenarioStoreTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/RequestJournalTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/journal/HeaderRedactionTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/provider/ProviderErrorsTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/sse/SseFramesTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiRequestsTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiRendererTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicRequestsTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicRendererTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJsonTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiChatIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiStreamIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/openai/OpenAiEmbeddingsAndModelsIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/anthropic/AnthropicMessagesIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/FaultInjectionIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/LatencyIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/JournalIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/AdminApiIntegrationTest.kt
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/error/MockErrorEnvelopeIntegrationTest.kt
mock-provider/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/mockprovider/container/MockProviderImageTest.kt   (integrationTest suite, ADR-016)
mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/FixedClockConfiguration.kt     (@TestConfiguration: fixed Clock for golden tests)
mock-provider/src/test/resources/golden/openai-chat.json
mock-provider/src/test/resources/golden/openai-chat-stream.sse
mock-provider/src/test/resources/golden/openai-chat-stream-include-usage.sse
mock-provider/src/test/resources/golden/anthropic-messages.json
mock-provider/src/test/resources/golden/anthropic-messages-stream.sse
docs/knowledge/systems/mock-provider.md
```

As built, also: `build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts` (ADR-FND-03-012) and the files the Log entry "Implementation (steps 1-7)" lists.

**Files edited:** `settings.gradle.kts`, `build.gradle.kts` (root), `gradle/libs.versions.toml` (one alias; as built also the BlockHound version), `build-logic/src/main/kotlin/llmgw.kotlin-jvm.gradle.kts` and `config/detekt/detekt.yml` (as built, ADR-FND-03-012), `.claude/skills/verify-gates/SKILL.md` (as built, ADR-016), `architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/Modules.kt`, `CLAUDE.md`, `docs/GLOSSARY.md`, `docs/diagrams/container-overview.md`, `.claude/skills/gateway-smoke/SKILL.md`, `docs/knowledge/INDEX.md`, `docs/DECISIONS.md` (ADR-014 revision), `docs/roadmap.md`, this spec. No `.gitattributes` change: `* text=auto eol=lf` already keeps the golden `.sse` files LF, which their `\n` framing needs (a Windows editor must not rewrite them as CRLF).

## 8. Tests

- **Unit (TDD, plain JUnit 5, no Spring context; the pure packages):**
  - `Fnv1aTest`: the 64-bit FNV-1a test vectors of draft-eastlake-fnv (empty string and "a" at least).
  - `TokenizerTest`: counts for words, digits, punctuation, accented letters, CJK, emoji; `pieces` concatenation equals the input for 200 random strings; whitespace-only input has zero pieces.
  - `ConversationTest`: OpenAI string, `null` and parts content; Anthropic system string and blocks, `tool_result` text; canonical string separates turns so `["ab","c"]` ≠ `["a","bc"]`; last user turn.
  - `TextGeneratorTest`: same seed same text; different seed different text; requested token count; only vocabulary words.
  - `CompletionPlannerTest`: modes `lorem`/`echo`/`fixed`; cap cuts and sets `length`; `max_tokens: 0`; P formula on a known conversation; overrides; `K'`/`W'` clamping; OpenAI and Anthropic usage projections; `omit`.
  - `EmbedderTest`: determinism; norm 1; `dimensions` 1, 3, 1536; identical inputs cosine 1; shared-word pair cosine above a disjoint pair; token arrays; punctuation-only input; base64 little-endian round trip.
  - `ScenarioStoreTest`: steps in order with `times`; `otherwise` after the steps; `*` fallback; member inheritance order (scenario `otherwise`, `*` `otherwise`, defaults); `put` restarts the cursor; **concurrency**: 1000 coroutines on `Dispatchers.Default` resolving one model whose scenario has `times: 37` error steps get exactly 37 errors.
  - `RequestJournalTest`: capacity and `dropped`; `since`, route, model and header filters; ordering by `seq`; epoch discard after `reset`; `seq` not rewound; 8 threads × 10 000 completions lose no count.
  - `HeaderRedactionTest`: `authorization`, `proxy-authorization`, `x-api-key`, `api-key`, `cookie`, `set-cookie`, and any name containing `auth`, `key`, `token`, `secret`, `password` or `cookie` (case-insensitive) become `[redacted]`; `traceparent`, `x-client-request-id`, `anthropic-version`, `content-type` are kept; fingerprint of `sk-test-abc` equals the precomputed SHA-256 prefix.
  - `ProviderErrorsTest`: one parameterized case per row of the section 4 defaults table, both APIs.
  - `SseFramesTest`: exact strings for an OpenAI data event, `[DONE]` and an Anthropic event; a JSON string containing `\n` is rejected (compact JSON has none; a newline would split the event).
- **Unit (JSON, plain JUnit with a `JsonMapper`):** `OpenAiRequestsTest`, `AnthropicRequestsTest` (every validation row of section 4, caps precedence, `include_usage`); `OpenAiRendererTest`, `AnthropicRendererTest` (golden bodies and SSE byte streams with a fixed clock and `seq`); `ScenarioJsonTest` (round trip; each rejection with its `param`).
- **Integration (`@SpringBootTest(RANDOM_PORT)`, WebTestClient and WebClient over real Netty, BlockHound installed through `blockhound-junit-platform`):**
  - `MockApplicationTest`: context loads; health 200; exactly one `ErrorWebExceptionHandler`; `Mono.fromCallable { Thread.sleep(1) }.subscribeOn(Schedulers.parallel())` fails with BlockHound's error.
  - `OpenAiChatIntegrationTest`: shape, determinism, ids, caps, `n: 2` 400, credential 401, usage invariants, `usage.omit`.
  - `OpenAiStreamIntegrationTest`: golden byte comparison with and without `include_usage` (`FixedClockConfiguration`, fresh context so `seq` = 1); stream and non-stream agree.
  - `OpenAiEmbeddingsAndModelsIntegrationTest`: float and base64; dimensions; validation; models list.
  - `AnthropicMessagesIntegrationTest`: non-stream and golden stream; `x-api-key` and bearer; 401 shape; `max_tokens` missing 400; cache fields with a `usage.cachedTokens` scenario.
  - `LatencyIntegrationTest`: TTFT and per-token timing (section 3); non-stream total ≈ ttft + perToken × C (± 150 ms); jitter bounds.
  - `FaultInjectionIntegrationTest`: every fault kind on both APIs, stream and non-stream (section 3 rows); the 10-concurrent-requests 429 case; a 503 step then success.
  - `JournalIntegrationTest`: redaction, fingerprint, body capture and truncation, header filter, `lastSeq` on an unknown path, reset semantics including an in-flight request across a reset; the credential string absent from `CapturedOutput` and every admin response.
  - `AdminApiIntegrationTest`: CRUD of scenarios; validation 400s leave state unchanged; `GET /__mock/scenarios` shows `stepsConsumed`.
  - `MockErrorEnvelopeIntegrationTest`: 404, 405, 406, 415, 400 bad JSON, 413 (with a lowered codec limit), a probe-free 500 path via a test-only failing route bean; provider shape chosen by path; no exception text in bodies.
  - Each integration class that changes scenarios calls `POST /__mock/reset` in `@BeforeEach` and runs on its own context; no class shares mock state with another (Gradle runs test classes serially in one JVM here).
- **Container (Testcontainers, Docker required; `src/integrationTest`, run by `./gradlew integrationTest` and `check`, ADR-016):** `MockProviderImageTest`: `GenericContainer(ImageFromDockerfile().withFileFromPath("Dockerfile", ...).withFileFromPath("mock-provider.jar", <mockProvider.jar>))`, exposed 8090, `Wait.forHttp("/actuator/health").forStatusCode(200)`, startup timeout 90 s; one chat completion 200.
- **Architecture:** ARCH-1 to ARCH-10 green with the role change; the deliberate ARCH-9 violation of section 3. No new rule, no new fixture.
- **Smoke scenarios (`gateway-smoke`, standalone mock, run by `gateway-tester`):**
  1. `./gradlew :mock-provider:bootRun` in the background; health `UP` on 8090.
  2. `curl -N` an OpenAI stream with `MOCK_DEFAULTS_LATENCY_PER_TOKEN=200ms` and `include_usage`: lines appear about 200 ms apart, then the usage chunk and `data: [DONE]`.
  3. Same for `/v1/messages` with `x-api-key`: the documented event order.
  4. `PUT /__mock/scenarios/mock-chat` with a 503 step `times: 2`: two 503s with the OpenAI body, then 200.
  5. `GET /__mock/stats` and `/__mock/requests`: counts and redacted `authorization`.
  6. Stop; `java -jar mock-provider/build/libs/mock-provider.jar` starts the same; stop.
  7. `docker build -t mock-provider:local -f mock-provider/Dockerfile mock-provider/build/libs` and `docker run --rm -p 8090:8090 mock-provider:local`: health and one completion; stop and remove.
  Report `docs/knowledge/runs/YYYY-MM-DD-mock-provider-standalone.md`. It cannot show gateway behaviour (no `/v1` endpoint on the gateway before GW-01).
- **Eval (`ai-eval`):** n/a.

## 9. Success metrics

Targets, measured once and recorded in the Log; not gates.

- Mock processing time with zero configured latency: p99 under 2 ms for a non-stream chat completion at 200 concurrent connections on the dev machine (a short k6 or WebClient loop; OPS-01 relies on the mock adding a fixed, small, stable delay).
- Stream timing error: each inter-event gap within 20 ms of the configured `perTokenMs` at p95 in `LatencyIntegrationTest`.
- `mock-provider` line coverage at least 85%, pure packages at least 95% (Kover report per package).
- `./gradlew :mock-provider:test` under 90 s warm, image test included.
- Zero journal entries or logs containing a test credential string across the whole suite.

## 10. ADRs (local)

### ADR-FND-03-001: What the mock is
- **Context:** Smoke and load runs need a real process on a port (gateway-smoke, OPS-01's k6). Adapter and pipeline tests need provider-exact bodies, SSE framing with controlled timing per event, stateful failure sequences ("fail twice, then succeed"), per-request outcomes such as "the client cancelled after 3 chunks", and deterministic usage. ADR-006 makes WebFlux with coroutines the project's server model.
- **Options considered:**
  - A) WireMock stubs (library or standalone). It has a request journal (`/__admin/requests`, `/__admin/requests/count`), fixed and random delays, faults (`CONNECTION_RESET_BY_PEER`, `EMPTY_RESPONSE`, `MALFORMED_RESPONSE_CHUNK`) and response templating that can read the request body with `jsonPath`. Rejected: its chunked dribble delay splits a body into equal chunks over a total duration, with no per-event TTFT versus inter-event control; its templating documentation mentions no SSE; deterministic usage from the request content, Anthropic's event sequence, and per-request outcomes such as client cancellation would all be hand-written stub files per test; its fault docs say `CONNECTION_RESET_BY_PEER` "will most likely cause the connection to hang rather than reset" on Windows, the development platform; and its journal documentation does not say whether `Authorization` is redacted.
  - B) An in-process test library (fake `ChatModelPort`-level or an embedded HTTP stub). Rejected: it cannot serve smoke and load runs, which need a separate process, and a port-level fake skips the adapters' HTTP and SSE code, the part most worth testing.
  - C) A plain Reactor Netty `HttpServer` without Spring. Lighter to embed, but routing, configuration binding, health and error mapping would be hand-written, and it would be the one server in the repository built differently.
  - D) A Spring Boot WebFlux application in its own module, coroutine handlers, raw SSE frames, its own admin API.
- **Decision:** D.
- **Consequences:** One more Spring Boot app (startup about the same as `gateway-app`), with the same conventions, rules and BlockHound discipline. Buys: one artifact for smoke, load and integration tests; exact control of framing and timing; behaviour as data (scenarios), not per-test stub files.
- **Date:** 2026-11-03

### ADR-FND-03-002: How integration tests reuse the mock
- **Context:** GW-01 needs FND-03 and FND-04 but not FND-02, so its tests cannot assume FND-02's base classes. ARCH-9 forbids a `gateway-*` module (main or test sources) from importing `.mockprovider`. Starting the mock's Spring context inside another app's test JVM would expose it to that app's whole classpath: Boot auto-configurations for R2DBC, Flyway, Redis or Kafka that `gateway-app` gains later would activate in the mock's context too.
- **Options considered:**
  - A) In-process: a `java-test-fixtures` launcher that starts `MockProviderApplication` on a random port inside the consumer's test JVM. Fast and debuggable. Rejected: the auto-configuration bleed above (a Flyway or R2DBC auto-configuration with no URL fails or migrates in the mock's context), a second `application.yaml` on one classpath, and an ARCH-9 exception (`testDependsOn`) just for this.
  - B) A child JVM started with `ProcessBuilder` from the consumer's tests (`java -jar`). No Docker, isolated classpath. Rejected: process lifecycle, port discovery and orphan cleanup on Windows are hand-written; Testcontainers already solves them.
  - C) A Testcontainers `GenericContainer` from an image built on the fly (`ImageFromDockerfile`) from `mock-provider/Dockerfile` and the boot jar, which the consumer resolves through a consumable Gradle configuration (`mockProviderBootJar`), per Gradle's variant-aware "share outputs between projects" guide (no reference to another project's tasks). The same Dockerfile serves compose later.
  - D) C with `bootBuildImage` (Cloud Native Buildpacks). Rejected: pulls a large builder image on first use, slow, and adds a second image recipe.
- **Decision:** C. FND-03 ships the Dockerfile, the consumable configuration and a container test of its own image; GW-01 adds the consumer side (a resolvable configuration, the jar path as a test system property, a small `GenericContainer` wrapper in its own test sources, which imports nothing of ours).
- **Consequences:** `./gradlew test` needs a running Docker from FND-03 on (CI's `ubuntu-latest` has it; `preflight` already checks Docker). Each consumer test class pays one container start (seconds); Testcontainers keeps the built image in Docker's cache between runs only if given a stable name, otherwise it rebuilds per JVM, which is cheap (one `COPY` layer on a cached base). Buys: full classpath isolation, the same artifact everywhere, no architecture-rule exception.
- **Date:** 2026-11-03
- **Superseded in part (2026-11-03)** by [ADR-016](../DECISIONS.md#adr-016): the Consequence "`./gradlew test` needs a running Docker from FND-03 on", the step 7 CLAUDE.md line saying the same, and the file list placing `MockProviderImageTest` in `src/test` no longer hold. Tests that need Docker live in `src/integrationTest` and run in the `integrationTest` suite, which `check` (so `./gradlew build` and CI) depends on; `./gradlew test` needs no Docker. Decision C stands. The suite's design is ADR-FND-03-012.

### ADR-FND-03-003: Module role `app`, depending on nothing of ours
- **Context:** ADR-014 lists `mock-provider` with role `tool` "(FND-03 may change the role)" and says Spring wiring annotations are allowed only in `app` modules (ARCH-6), and that a role change is a local ADR plus an edit of the table here and in `docs/DECISIONS.md`.
- **Options considered:**
  - A) Keep `tool`, avoid wiring annotations (functional bean registration only). Rejected: `@SpringBootApplication` itself is a wiring annotation under ARCH-6, and contorting the app to dodge the rule hides what the module is.
  - B) Role `app`, `dependsOn` empty. ARCH-6 allows its wiring; ARCH-10 confines its error mapper to `.mockprovider.error`; ARCH-2 and ARCH-3 have nothing to check (no core or adapter in its family); ARCH-9 keeps it from importing any of our packages.
  - C) Role `app` with `dependsOn: gateway-core`, to reuse `ErrorReply` or `RequestId`. Rejected: the mock imitates providers, whose shapes are not the gateway's; sharing types would let one bug pass in both the gateway and its double.
  - D) Split into `mock-provider-core` (pure) and `mock-provider` (app). Rejected: a module outside the ADR-014 table and more build configuration for a test tool; pure packages and plain-JUnit tests give the same testability.
- **Decision:** B.
- **Consequences:** `Modules.kt` changes one role; the ADR-014 table and a dated revision line go to `docs/DECISIONS.md` (proposed text in the hand-back). Buys: the rules apply unchanged, and the double stays independent of what it tests.
- **Date:** 2026-11-03

### ADR-FND-03-004: How tests control behaviour
- **Context:** Smoke runs and gateway integration tests reach the mock through the gateway, which forwards the model name and body but not arbitrary client headers. RTE-02 needs stateful sequences (fail N times, then recover); OPS-01 needs a fixed latency for a whole run; tests may run concurrently against one mock.
- **Options considered:**
  - A) Per-request headers (`x-mock-latency`, `x-mock-fail`). Rejected: the gateway does not forward them, so they work only when a test calls the mock directly; stateless, so no "fail twice".
  - B) Magic model names (`mock-fail-503`, `mock-slow-500`). Passes through the gateway, no setup. Rejected: a second control language, no sequences, and every combination becomes a name.
  - C) Directives inside the prompt. Rejected: they would pass through guardrails and the cache key, coupling unrelated tests to the mock's syntax.
  - D) An admin API with per-model scenarios: ordered steps with `times`, consumed atomically in arrival order, then an `otherwise` behaviour; a `*` scenario for every other model; member-level inheritance down to configured defaults settable from the environment; `reset`.
- **Decision:** D. Scenario keys are model names because that is the one request attribute every caller controls and the gateway forwards (after route resolution). Concurrent tests isolate by using distinct model names and never resetting a shared mock; a test that resets owns its mock instance.
- **Consequences:** Tests make one admin call before acting. Two tests that share a model name on one mock interfere; the systems entry states the rule. Buys: one control language that works directly, through the gateway, and in smoke runs, with exact counts under concurrency (one atomic increment per request, no lock).
- **Date:** 2026-11-03

### ADR-FND-03-005: Deterministic content and approximate usage
- **Context:** CACHE-01 needs identical requests to produce identical responses and different requests different ones; GW-02 and RL-02 need stream and non-stream to agree; RL-02 and COST-02 need usage numbers a test can predict; GW-01 must exercise the "usage omitted, estimate flagged" path. Real token counts need each provider's tokenizer.
- **Options considered:**
  - Content: C1) echo the prompt only (cache tests cannot tell two prompts' answers from the prompts themselves, and output guardrail tests need text the prompt did not contain); C2) a fixed reply for every request (a cache bug serving the wrong team's or prompt's entry would be invisible); C3) text seeded by a hash of model and conversation (`lorem`), plus `echo` and `fixed` modes on demand.
  - Tokens: K1) a real tokenizer library for OpenAI encodings. Rejected: a dependency that is still wrong for Anthropic, and realism is not the goal; K2) `ceil(chars / 4)`, OpenAI's rule of thumb. Rejected: a piece of streamed text would not map to a whole number of tokens, so `completion_tokens` and the chunk count would disagree; K3) a regex tokenizer (letter/digit runs and single symbols), one piece per token, and the cookbook's per-message overhead for prompts.
  - Seed hash: SHA-256 (JCA lookup on the event loop on first use) versus FNV-1a 64 (pure Kotlin).
- **Decision:** C3 with K3 and FNV-1a. `completion_tokens` equals the number of streamed content pieces by construction. Anthropic's input is split into uncached, cache-read and cache-creation parts so that the three always sum to the OpenAI prompt count.
- **Consequences:** Usage numbers are documented as approximate and never compared with a real provider's. Tests assert properties (determinism, invariants), not `lorem` words. Buys: reproducible cache and reconciliation tests, and a stream whose chunks and usage agree exactly.
- **Date:** 2026-11-03

### ADR-FND-03-006: Mock embeddings
- **Context:** GW-05 and CACHE-02 need `/v1/embeddings` with real shapes; the `semantic-cache` eval measures quality with real (local) models, not the mock.
- **Options considered:**
  - A) A constant vector. Rejected: every input would be a semantic-cache hit.
  - B) A pseudo-random unit vector seeded per input. Identical inputs match; any rewording is orthogonal, so CACHE-02 could never test a near-duplicate hit.
  - C) Feature hashing of words into signed buckets, L2-normalised (Weinberger et al., 2009). Shared words give positive cosine similarity, disjoint texts are near-orthogonal, deterministic, no model.
  - D) A small local embedding model in the mock. Rejected: weights, memory and startup cost in every test, and the stack already provides local models for evals.
- **Decision:** C, with `dimensions` and both encodings.
- **Consequences:** CACHE-02 can test thresholds mechanically (identical, reworded with shared words, unrelated). Nobody may use mock vectors to tune the real threshold (`ai-eval` uses real models). Buys: meaningful-enough similarity at zero cost.
- **Date:** 2026-11-03

### ADR-FND-03-007: Writing SSE frames
- **Context:** Providers frame events as `data: <json>` (OpenAI, ending `data: [DONE]`) and `event: <type>\ndata: <json>` (Anthropic), with a space after the colon. Spring's `ServerSentEvent` writes `data:` and `event:` with no space; the WHATWG SSE parser strips one leading space, so both parse the same, but golden byte comparisons and naive line-matching clients do not. Each event must reach the client when it is produced, or TTFT tests are meaningless.
- **Options considered:**
  - A) Return `Flow<ServerSentEvent<String>>` and let Spring's writer frame it. Rejected: framing differs from the providers' bytes; the writer flushes through `writeAndFlushWith`, but whether each event is flushed separately depends on the server response implementation (not shown in the writer).
  - B) Return `Flow<String>` with `text/event-stream`. Rejected: Spring wraps each element as an SSE `data:` event, adding a second framing.
  - C) Build each frame as a string in pure code (`SseFrames`) and write it with a custom `BodyInserter` that calls `ServerHttpResponse.writeAndFlushWith`, one inner publisher per event: "flushing after each `Publisher<DataBuffer>`" (Spring Framework javadoc).
- **Decision:** C. `SseFrames` rejects JSON containing a newline (compact JSON never has one) so an event cannot be split.
- **Consequences:** Golden files compare exact bytes; timing tests measure real per-event delivery. GW-02 can reuse the same technique for the gateway's own SSE output (proposed pattern in the hand-back). Buys: provider-exact framing and explicit flush points.
- **Date:** 2026-11-03

### ADR-FND-03-008: Request journal, counter and credential handling
- **Context:** Smoke and integration tests must prove that a request did or did not reach the provider, and what it contained (GRD-02: redacted before the provider saw it). CLAUDE.md rule 2 forbids secrets in logs and fixtures. GW-04 and GW-01 need to show that the gateway sends its own provider key upstream and never the client's team key.
- **Options considered:**
  - Storage: S1) log lines parsed by tests (fragile, and puts bodies in logs); S2) an unbounded list (memory grows under load); S3) a fixed-capacity lock-free ring of immutable entries plus lock-free counters, served over `/__mock`.
  - Counting point: at handler entry (misses misrouted calls) versus a first `WebFilter` for every non-admin path (chosen: a misrouted call must count).
  - Credentials: R1) record nothing (cannot test that the right key was sent); R2) record presence only (cannot tell the provider key from a forwarded team key); R3) record the scheme and the first 8 hex digits (32 bits) of SHA-256 of the value, never the value; R4) record the value (rejected: rule 2).
  - Other headers: allowlist (tests miss headers they need, such as `traceparent`) versus redaction by name pattern (chosen; new secret-looking names are caught by the `auth`/`key`/`token`/`secret`/`password`/`cookie` substrings).
- **Decision:** S3, filter-level arrival counting with an epoch for `reset`, R3, pattern redaction, bodies kept in memory up to 16 KiB each and never logged.
- **Consequences:** A test computes the fingerprint of its own fake key to assert which key arrived. A 32-bit prefix of SHA-256 does not reveal a high-entropy key, and only fake keys reach the mock. The journal holds request bodies, acceptable because the mock is never pointed at real traffic; load runs switch capture off. Buys: one authoritative "did it reach the provider" signal (`lastSeq`), precise correlation by header, bounded memory.
- **Date:** 2026-11-03

### ADR-FND-03-009: Coverage and BlockHound for a non-core app module
- **Context:** CLAUDE.md: 80% lines per core module, 70% overall. The mock is an app module whose classes join the merged report. FND-01 planned BlockHound for FND-02 or GW-02.
- **Options considered:**
  - Coverage: V1) a per-module minimum in `mock-provider/build.gradle.kts`; V2) the merged 70% rule only, plus the `MockProviderApplicationKt` exclusion (ADR-FND-01-006 allows that exclusion and asks for an ADR for any other); V3) exclude the mock from the merge (rejected: untested test infrastructure is how a fake drifts from the provider).
  - BlockHound: B1) wait for FND-02/GW-02; B2) enable it in `mock-provider`'s tests now, in the module's own build file, without touching `llmgw.spring-app` (which would also turn it on in `gateway-app`, FND-04's open DRBG question).
- **Decision:** V2 with a recorded target (section 9, 85%), and B2.
- **Revised 2026-11-03:** BlockHound moved from `mock-provider/build.gradle.kts` to `llmgw.spring-app`, so every app module's test JVMs run under it. The mock's dependency and JVM flag are unchanged, and FND-04's DRBG question is settled (FND-04 Log).
- **Consequences:** The mock cannot pull the merged number down without the gate failing. U8 (`-XX:+AllowRedefinitionToAddDeleteMethods` on JDK 25) is exercised first here; if BlockHound misbehaves, the fallback is to keep `SleepInsteadOfDelay` and drop BlockHound from the mock with a gotcha entry. Buys: the latency simulation is proven non-blocking, which OPS-01's numbers depend on.
- **Date:** 2026-11-03

### ADR-FND-03-010: JSON handling
- **Context:** Inbound provider requests carry many members the mock ignores and evolve often; outbound bodies need exact member presence (OpenAI's `usage` absent versus `null`, Anthropic's `stop_sequence: null`) and order for golden files. GW-01 decides the gateway's JSON library (FND-04 left it open); the mock must not pre-empt it.
- **Options considered:**
  - A) Kotlin data classes with Jackson and its Kotlin module. Presence-versus-null needs per-field annotations; inbound classes would model members the mock does not use.
  - B) kotlinx.serialization. Rejected: a second JSON stack in a Boot app whose codecs default to Jackson, chosen before GW-01 decides for the gateway.
  - C) Jackson 3 tree model (`JsonNode` in, `ObjectNode` out) through Boot's configured mapper; pure packages see only Kotlin values.
- **Decision:** C. No Jackson Kotlin module is added.
- **Consequences:** Renderers are explicit builders, verbose but exact; unknown inbound members are ignored by construction. Whatever GW-01 picks, the mock does not change. Buys: exact wire control, no annotations, and no dependency on GW-01's choice.
- **Date:** 2026-11-03

### ADR-FND-03-011: Scope cut
- **Context:** The row asks for a fake "enough for every test and smoke run". The next consumers are GW-01, GW-02, GW-03, GW-05, RL-02, RTE-02, CACHE-01, GRD-02 and OPS-01.
- **Options considered:**
  - A) Text completions, embeddings and models only; tool calls, images, the Responses API, `n > 1`, logprobs, stop sequences and Anthropic's models and token-counting endpoints out. Tool-call requests are accepted and ignored.
  - B) A plus scripted tool calls (OpenAI `tool_calls` deltas, Anthropic `tool_use` with `input_json_delta`). Rejected for FND-03: about one more step of work whose exact needs (argument chunking, parallel calls) are set by GW-03 and GW-06; doing it now would guess them.
  - C) A full provider simulator. Rejected: non-goal.
- **Decision:** A. GW-03 (Anthropic tool-call translation) and GW-06 (tool calling pass-through) extend the mock with scripted tool calls as part of their own items; `CompletionPlan` holds a list of content parts so that extension adds a part type.
- **Consequences:** GW-03's and GW-06's plans include a mock step. Buys: FND-03 stays at seven half-day steps and every listed consumer except tool calling is served.
- **Date:** 2026-11-03
- **Revision (2026-11-04):** the code never matched this: `CompletionPlan` is `pieces: List<String>`, not a list of content parts, so scripted tool calls are a refactor rather than an added type. GW-03 does that refactor and adds scripted tool calls on both provider shapes, so GW-06 no longer has a mock step for them (ADR-GW-03-009, ADR-GW-03-019).

### ADR-FND-03-012: The `integrationTest` suite
- **Context:** [ADR-016](../DECISIONS.md#adr-016) (2026-11-03) moves tests that need Docker out of `test` into a per-module `integrationTest` suite wired into `check`, and leaves its construction to FND-03 ("a convention plugin or Gradle JVM test suites, its local choice"). FND-03 has the first such test (`MockProviderImageTest`); FND-02 and every adapter item add more. ADR-014's checklist has each module apply exactly one convention plugin. Coverage (Kover), detekt and ktlint must cover the new source set without counting test code as production code.
- **Options considered:**
  - A) An opt-in convention plugin a module applies when it has Docker tests. Rejected: a module would apply two convention plugins, against ADR-014's "exactly one" and acceptance criterion 1 of this spec.
  - B) One `test` task with a JUnit tag (`docker`) excluded by default and run by a second task. Rejected by ADR-016 (one source set and classpath for both, and a missing daemon is easy to turn into a skip).
  - C) A convention plugin `llmgw.integration-test`, applied by `llmgw.kotlin-jvm`, registering a Gradle `JvmTestSuite` named `integrationTest` in every module.
- **Decision:** C:
  - Sources in `src/integrationTest/kotlin`; a module without them has an empty suite (`NO-SOURCE`).
  - JUnit Jupiter at the catalog's `junit` version; tag `live` excluded, as in `test`.
  - `integrationTestImplementation` and `integrationTestRuntimeOnly` extend the `test` configurations, so the suite has the same libraries, BlockHound included. Docker libraries are added only in a module's own build file (`mock-provider`: the Testcontainers BOM and `testcontainers-core`).
  - `check` depends on the suite and on `detektIntegrationTest`; ktlint lints the source set without configuration. The suite runs after `test`.
  - No `disabledWithoutDocker`: without a daemon the suite fails with Testcontainers' message, never skips.
  - Kover instruments both suites' test tasks, so what `integrationTest` exercises in `main` counts; the suite's own source set is excluded from the report (`kover.currentProject.sources.excludedSourceSets`; [gotcha](../knowledge/gotchas/kover-excludes-only-the-test-compilation-by-default.md)).
  - detekt's test exclusions are extended with `**/integrationTest/**` for the nine active rules that exclude `**/test/**` by default ([gotcha](../knowledge/gotchas/detekt-default-test-excludes-miss-integration-test.md)).
  - In `mock-provider`, the `integrationTest` task takes the boot jar as a file input and passes its path as `-DmockProvider.jar` through a `CommandLineArgumentProvider` that captures a local provider, which the configuration cache accepts ([gotcha](../knowledge/gotchas/gradle-configuration-cache-rejects-script-object-in-lambda.md)).
- **Consequences:** `./gradlew test` needs no Docker; `./gradlew build`, `check` and `koverVerify` do (CLAUDE.md Testing, `verify-gates`). FND-02 adds its base classes and dependencies to a suite that already exists. Every module pays one more (empty) source set and a few `NO-SOURCE` tasks. Verified 2026-11-03: with no reachable daemon `:test-support:integrationTest` exits 1 with 11 tests failed, 0 passed, 0 skipped, the first with Testcontainers' `Could not find a valid Docker environment`. Docker Desktop stayed up: `DOCKER_HOST=tcp://127.0.0.1:1` alone still passes because Testcontainers falls through to the npipe, so the run also hid the OS from the test JVM ([gotcha](../knowledge/gotchas/testcontainers-tries-every-strategy-so-a-bad-docker-host-reaches-the-npipe.md)). Buys: one place that defines the suite, the "exactly one convention plugin" rule kept, and coverage that includes container-tested code.
- **Date:** 2026-11-03

## 11. Log

Append-only, newest at the bottom. Every session on this item ends with an entry.

### 2026-11-03: Spec written
- **Who:** gateway-architect
- **Done:** This spec: contract (provider routes, framing, error defaults, admin API, journal), design (packages, generation, usage, embeddings, faults, in-memory data and concurrency), failure model, tests, file list and doc edits for the implementer. Public references fetched 2026-11-03 (OpenAI chat, chunk, embeddings, models, error-codes and rate-limit references; Anthropic messages, streaming, errors and rate-limit pages; openai-python streaming and embeddings sources; Spring `ServerSentEvent` and `ReactiveHttpOutputMessage`; Gradle output sharing; Testcontainers image building; Temurin tags; WireMock faults, verification and templating).
- **Decisions:** ADR-FND-03-001 to -011: a Spring Boot WebFlux app module on 8090 with role `app` and no dependency on our modules; integration reuse through a Testcontainers image built from the boot jar and a consumable Gradle configuration; per-model scenarios over `/__mock` (steps consumed atomically, `otherwise`, `*`, inherited defaults); hash-seeded deterministic content with a regex token approximation and the cookbook's message overhead; feature-hashed embeddings; raw SSE frames with one flush per event; a lock-free bounded journal with pattern redaction and a 32-bit SHA-256 credential fingerprint; merged-coverage rule only and BlockHound in the mock's tests; Jackson tree model; tool calls left to GW-03/GW-06. Proposed for `docs/DECISIONS.md`: an ADR-014 revision line (role `app`).
- **Unverified, settled during implementation:**
  - Reactor Netty closes the connection without the terminating chunk when the body publisher errors after commit (FND-04 states it for the gateway; the fault tests confirm it here).
  - `writeAndFlushWith` with one inner publisher per event flushes each event to the socket (timing test).
  - A client-side timeout or disconnect cancels the server-side handler coroutine for both the `delay` phase and a running stream (`client_cancelled` tests).
  - `ServerResponse.BodyBuilder.body(BodyInserter)` from a `coRouter` handler hands the inserter a `ServerHttpResponse` on which `writeAndFlushWith` is honoured.
  - The Testcontainers 2.0.5 core coordinate `org.testcontainers:testcontainers` and `ImageFromDockerfile` behaviour on Windows Docker Desktop.
  - BlockHound with `-XX:+AllowRedefinitionToAddDeleteMethods` on JDK 25 (FND-01 U8), and that `MessageDigest.getInstance("SHA-256")`, warmed in `CredentialFingerprint`'s constructor, does not trip BlockHound on the event loop.
  - That `server.shutdown: immediate` is the Boot 4.1.2 key and value (graceful-shutdown docs).
  - What OpenAI sends after an in-band stream error (the mock ends the stream without `[DONE]`); whether OpenAI non-stream bodies still carry `refusal` and `annotations` on every message (taken from the current reference shape).
- **Next:** Step 1 (module skeleton and rules).

### 2026-11-03: Implementation (steps 1-7)
- **Who:** implementer (general-purpose subagent of feature-loop)
- **Done:** Module `mock-provider` (Spring Boot WebFlux app on 8090, role `APP`, empty `dependsOn`) with every file of section 7. Added beyond the list: `config/ServerConfiguration.kt`, `admin/ScenarioJsonWriter.kt`, `error/MockRejection.kt`, `error/MockErrorTranslator.kt`, `error/LoggableFailure.kt`, `web/Replies.kt`, `web/ErrorBodies.kt`, `web/Generation.kt`, `web/Inbound.kt`, `web/JsonReads.kt`, `web/RequestRecord.kt`; tests `Golden.kt`, `MockHttp.kt`, `ShutdownIntegrationTest`, `error/LoggableFailureTest`, `error/ContentTooLargeIntegrationTest`; `build-logic/src/main/kotlin/llmgw.integration-test.gradle.kts`; the systems entry and [patterns/raw-sse-frames-flushed-per-event](../knowledge/patterns/raw-sse-frames-flushed-per-event.md). Doc edits of step 7 applied (CLAUDE.md row and lines, glossary, diagram note, `gateway-smoke` and `verify-gates` skills, INDEX); the orchestrator applied the ADR-014 revision and wrote [ADR-016](../DECISIONS.md#adr-016). `MockProviderImageTest` moved to `src/integrationTest` (ADR-016; ADR-FND-03-012 records the suite). Gates 1-7 green: 391 tests, 0 failures (gateway-core 41, gateway-app 43, mock-provider 273, architecture-tests 34), `integrationTest` 2/2; Kover mock-provider 97.99% (1556/1588), merged 97.70%; pure packages `generation`, `behaviour`, `provider`, `sse` 100%, `journal` 99.3%.
- **Acceptance evidence:** every section 3 line maps to a section 8 test. Deliberate violations, each observed failing and reverted: an import of `GatewayError` in `sse/SseFrames.kt` failed ARCH-9 naming that file; a `Thread.sleep(10)` in `Serving.respond` failed detekt (`SleepInsteadOfDelay`) and BlockHound (`Blocking call! java.lang.Thread.sleepNanos0`). `bootRun` health returned `{"groups":["liveness","readiness"],"status":"UP"}`; `outgoingVariants` lists `mockProviderBootJar` (category `mock-provider-boot-jar`, artifact `mock-provider.jar`). The single-`ErrorWebExceptionHandler` assertion lives in `MockErrorEnvelopeIntegrationTest`, because ARCH-10 also scans test sources.
- **Unverified points of the spec, settled (no spec fallback needed):**
  - `writeAndFlushWith` with one inner publisher per event flushes each event: gaps of 200 to 202 ms at `perTokenMs: 200` (`LatencyIntegrationTest`; p95 deviation about 2 ms against section 9's 20 ms).
  - An error from the body publisher after commit makes Reactor Netty close the connection without the terminating chunk (WebClient `PrematureCloseException`; a raw socket reads `200`, part of the body, EOF without a zero chunk).
  - A client disconnect cancels the handler both in the latency wait (`hang`) and mid-stream (`stall`). detekt 2's `SuspendFunSwallowedCancellation` flags any statement before the rethrow in a `catch (CancellationException)`, so the outcome is recorded in `finally` by `!currentCoroutineContext().isActive`; the filter and the stream can be cancelled in either order, so whichever leaves last writes the entry (`RequestRecord.join`/`leave`).
  - `coRouter` with `ServerResponse.BodyBuilder.body(BodyInserter)` works.
  - Testcontainers 2.0.5: the core coordinate is `org.testcontainers:testcontainers`; `ImageFromDockerfile` works on Windows Docker Desktop (npipe, Docker 29.3.1), first run about 16 s. The catalog alias is `testcontainers-core`: `testcontainers` would be a prefix of `testcontainers-bom` ([gotcha](../knowledge/gotchas/gradle-version-catalog-alias-prefix-needs-asprovider.md)).
  - BlockHound works on JDK 25 with `-XX:+AllowRedefinitionToAddDeleteMethods` (the JVM warns that the option is deprecated). The version is `1.0.18.RELEASE`; the catalog's `1.0.18` did not resolve and was corrected ([gotcha](../knowledge/gotchas/blockhound-versions-carry-release-suffix.md)). The warmed `MessageDigest` does not trip it. BlockHound caught `ReactiveFlowKt`'s static `ServiceLoader` read on the event loop at the first `CoWebFilter` request; `MockConfiguration.kt:75` loads the class with `Class.forName` at startup ([gotcha](../knowledge/gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use.md)).
  - `server.shutdown: immediate` is the Boot 4.1.2 key and value, but alone the context took about 5 s to close with a held request: Reactor Netty waits 3 s for open connections, then a 2 s quiet period. Added `spring.reactor.netty.shutdown-quiet-period: 0s` and a Netty lifecycle timeout in `ServerConfiguration` (1 s at first, 3 s after review; it also bounds the startup bind) ([gotcha](../knowledge/gotchas/reactor-netty-stop-waits-dispose-timeout-and-quiet-period.md)).
  - Not settled here (provider behaviour, not observable without a paid call): what OpenAI sends after an in-band stream error, and whether every OpenAI non-stream message carries `refusal` and `annotations`. The mock keeps the reference shapes.
- **Found:** `gateway-app`'s `LoggableFailure` logs one stack frame after Spring's checkpoint ([bug](../knowledge/bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint.md)); the mock's copy takes the frames back from the checkpoint.
- **Next:** review.

### 2026-11-03: Review and fixes
- **Who:** gateway-reviewer (two rounds: findings, then the fixes); fixes by the implementer
- **Done:** Round 1 found 1 FND-04 bug, 3 medium and 7 low. All fixed test-first (each new test red before its fix):
  1. FND-04 (medium): `gateway-app`'s `LoggableFailure` copied the one-frame trace the checkpoint leaves and dropped the checkpoint holding the rest; its test compared the copy with the already-truncated original. Fixed in a separate commit (`Roadmap: FND-04`): `gateway-app/.../error/LoggableFailure.kt:28-32` takes the frames from the checkpoint; `LoggableFailureTest.kt:16` records them before the checkpoint runs, `:29-30` compares with them and asserts a frame of the test class ([bug](../knowledge/bugs/loggable-failure-logs-one-frame-after-reactor-checkpoint.md), [gotcha](../knowledge/gotchas/reactor-checkpoint-truncates-error-stack-to-first-frame.md)).
  2. Medium: after its first `kotlinx.coroutines.delay` the mock ran on kotlinx's single `DefaultExecutor` thread, which BlockHound does not watch ([bug](../knowledge/bugs/mock-serving-after-delay-runs-on-default-executor.md), [gotcha](../knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)). Every wait is now `awaitDelay` on Reactor's `parallel` timer (`Serving.kt:253-255`). `ServingThreadIntegrationTest` (resumes on `parallel-*`; a `Thread.sleep` after the wait is caught by BlockHound) and `ReactorDelayTest`. This also makes the deliberate `Thread.sleep` violation fail when placed after a latency wait.
  3. Medium: a step's `times` was expanded into one object per request, so `"times": 2147483647` gave `OutOfMemoryError` ([bug](../knowledge/bugs/mock-scenario-times-expanded-into-memory.md)). Steps are kept unexpanded with cumulative ends and a capped `Long` cursor (`ScenarioStore.kt:101-118`); `ScenarioJson` caps a scenario at 1000 steps and `times` at 1 000 000 (`ScenarioJson.kt:37-39`, `:56`, `:202-203`).
  4. Medium: timing assertions with upper bounds (first content event within 150 ms of the role chunk, shutdown within 2 s) would flake on a loaded CI runner. `LatencyIntegrationTest` now asserts lower bounds and the spread of the pieces (at least 600 ms), measured from the request start; `ShutdownIntegrationTest` asserts under 4 s. Section 3 criteria reworded the same day.
  5. Low: `error.headers` names and values were stored unchecked, so a typo became a 500 on every request ([bug](../knowledge/bugs/mock-scenario-error-headers-not-validated.md)); now RFC 9110 tokens and values without CR, LF or NUL, 400 at `PUT` (`ScenarioJson.kt:98-110`).
  6. Low: embeddings `input` had no item cap; now at most 2048 items, OpenAI's limit (`OpenAiRequests.kt:73`, `:146`; `OpenAiRequestsTest` "embeddings take at most 2048 inputs").
  7. Low: journal races. `arrive` reads the epoch before taking the sequence (`RequestJournal.kt:38-41`), so an arrival racing a reset is dropped with the old epoch; a ring slot keeps the higher write number when two writers a capacity apart race (`:126`); an entry is written before its count (`:52-54`). `RequestJournalTest` "a small ring under concurrent writes keeps exactly capacity distinct entries".
  8. Low: the per-request DEBUG line printed the client's model name raw; control characters are now escaped (`ArrivalWebFilter.kt:121`, `ArrivalWebFilterTest`).
  9. Low: credentials are checked before the body is read, so no key and a bad body is 401 (`OpenAiChatIntegrationTest`, `AnthropicMessagesIntegrationTest` "credentials are checked before the body").
  10. Low: the Netty lifecycle timeout went from 1 s to 3 s, because it is also the startup bind timeout (`ServerConfiguration.kt:36`).
  11. Low (docs): a missing newline in the INDEX, systems entry contract 3 reworded (steps are taken in the order requests resolve their scenario, after the body is read), and the Dockerfile base image pinned by digest.
- **Tests:** 391 → 412, 0 failures (mock-provider 273 → 294); `integrationTest` 2/2.
- **Knowledge:** the reviewer wrote the 4 bug entries and the gotchas on BlockHound's version suffix, detekt's test excludes, the configuration cache and script objects, `ReactiveFlowKt`, Kover's source sets, the checkpoint frame cut, `DefaultExecutor` and Reactor Netty's stop timing.
- **Not fixed, accepted:** no Konsist rule checks that the pure packages (`behaviour`, `generation`, `journal`, `provider`, `sse`) import neither Spring, Reactor nor Jackson; it is a convention, stated in the systems entry. See Closed.
- **Next:** smoke.

### 2026-11-03: Smoke and fix
- **Who:** gateway-tester; fix by the implementer
- **Done:** [runs/2026-11-03-smoke-mock-provider-and-errors](../knowledge/runs/2026-11-03-smoke-mock-provider-and-errors.md), verdict pass-with-findings: `mock-provider` and `gateway-app` by `bootRun`, 26 scenarios (24 pass, 1 pass with a finding, 1 informational), dummy keys only, no provider called. Findings:
  1. Every `disconnect` abort writes one ERROR line from Reactor Netty's `HttpServerOperations`, with no request ID or query. Expected for that fault; documented in the systems entry (Failure behaviour), and the logger stays on.
  2. A request rejected by body validation was journaled with `model: null`, so `?model=` and the per-model counts missed a model's 400s. Fixed: `RequestRecord.noteModel` (`Inbound.kt:44`) records the body's `model` before validation, called at `OpenAiHandlers.kt:30`, `:61` and `AnthropicHandler.kt:24`; `JournalIntegrationTest` "a body that fails validation is journaled with the model it named" (`:122`), red before.
  3. A control character in a header value is a bare Reactor Netty 400 before WebFlux (no envelope, no `x-request-id`, one WARN line) ([gotcha](../knowledge/gotchas/reactor-netty-rejects-control-chars-in-header-values-before-webflux.md), linked from the `request-context-and-errors` systems entry).
  4. `gateway-app` logs nothing for 4xx at the default level (by design).
- **Not covered by the smoke:** section 8 steps 6-7 (`java -jar`, `docker build` + `docker run`), `hang`, `malformed`, `usage.omit`, holds reaching `mock.max-hold`. Covered instead by `MockProviderImageTest` (the image built from the Dockerfile and boot jar, entry point `java -jar`), `FaultInjectionIntegrationTest` ("hang - a client that gives up after 500 ms ...", "hang - held until mock max-hold, then 504 ... timed_out", "malformed - ..."), `OpenAiChatIntegrationTest` "usage omit removes the usage member", `OpenAiRendererTest` (the usage chunk) and `AnthropicMessagesIntegrationTest` "usage omit removes usage from the body and from both stream events".
- **Tests:** 412 → 413 (mock-provider 295).
- **Next:** close the docs, commit.

### 2026-11-03: Closed
- **Who:** docs-update (feature-loop)
- **Done:** Gates rerun after every fix: `./gradlew build` exit 0; `test` gateway-core 41, gateway-app 43, mock-provider 295, architecture-tests 34, 0 failures; `integrationTest` mock-provider 2/2; Kover mock-provider 97.90% (1587/1621), gateway-app 93.96%, merged 97.64% (thresholds 80% core, 70% overall); ktlint and detekt clean; rule scans clean; `docs-check` exit 0. Acceptance criteria and plan steps ticked, none left open; the "not buffered" and shutdown criteria were reworded (review finding 4). ADR-FND-03-012 added; ADR-FND-03-002 marked superseded in part by ADR-016; file list corrected. Bug entries' Fix and Prevention closed; line references in the systems entry, pattern, gotchas and run report refreshed; roadmap row `[x]`; INDEX.
- **Gate 6 (clean-room scan) skipped:** `.clean-room-denylist` is empty. The user chose "Seguir sem por ora" on 2026-11-03.
- **Eval n/a:** no threshold, classifier or routing policy; the mock's embeddings are not semantic.
- **Section 9 metrics:** stream timing p95 deviation about 2 ms (target 20 ms); line coverage 97.90% (target 85%), pure packages at least 99% at implementation; no test credential string in any journal entry, response or captured log (`JournalIntegrationTest`). Not measured: p99 processing time at 200 concurrent connections (OPS-01 measures the mock as its upstream) and the warm `:mock-provider:test` duration.
- **Accepted risk:** the purity of the pure packages is not enforced by a Konsist rule. A violation would cost the testability of a test double, not the gateway's correctness; no roadmap row. Revisit if a later item copies the "pure packages inside an app module" layout into a gateway module.
- **Environment:** the Docker base images `eclipse-temurin:25-jre-noble` (pinned by digest in the Dockerfile) and `testcontainers/ryuk:0.14.0` remain pulled locally; every `integrationTest` run uses them.
- **Handed on (not FND-03 criteria):** GW-01 adds the consumer side of the image (a resolvable configuration for `mockProviderBootJar`, the jar path as a system property, a `GenericContainer` wrapper); GW-03 and GW-06 add scripted tool calls (ADR-FND-03-011); GW-02 confirms on a real socket that a committed error logs one mapper line plus Reactor Netty's `HttpServerOperations` line; a run with Docker stopped confirms that `integrationTest` fails rather than skips.
- **Next:** two commits: the `gateway-app` `LoggableFailure` fix (`fix(gateway)`, `Roadmap: FND-04`), then FND-03 (`Roadmap: FND-03`).

### 2026-11-03: Test gaps and second-review fixes
- **Who:** implementer (subagent), with fixes from `gateway-reviewer`'s second pass
- **Done, each test red before its fix unless noted:**
  1. `OpenAiStreamIntegrationTest:82`: `usage.omit` with `include_usage` over HTTP sends no usage chunk, and every chunk keeps `"usage":null` (section 5, Usage). The behaviour already existed, so it was proven by removing `!plan.usage.omit` from the renderer: red, then restored.
  2. After commit the mapper replaces any failure with a stackless `MockConnectionAbort`, and intentional aborts are decided by type only (`MockErrorWebExceptionHandler.kt:55`). `MockCommittedResponseIntegrationTest`, raw socket.
  3. `ScenarioJson.isHeaderValue` mirrors Netty's value rule ([bug](../knowledge/bugs/mock-scenario-error-headers-not-validated.md)).
  4. `RequestJournal.complete` decrements `inFlight` after the entry is visible. `RequestJournalTest:171` failed on its first round.
  5. `LoggableFailure` keeps an identity `seen` set, as gateway-app's copy does.
  6. `tools/smoke/stack-check.sh`: `grafana-host` passes only on a 3xx (it passed on 403 and 500), and `services` compares stdout only (a warning from one compose command failed it). `scripts/preflight.sh`: `stack_port` reads `export X=` and strips inline comments. Old and new versions run against stubs; the real stack gave 12 PASS.
- **ADR-FND-03-012 closed:** a run without a reachable daemon fails and skips nothing; the line "a run with Docker stopped confirms ..." handed on at closing is done (the ADR has the numbers).
- **Tests:** mock-provider 307 (`test`), 2 (`integrationTest`). Kover mock-provider 98.04%.
- **Next:** commit (`Roadmap: FND-03`).

### 2026-11-03: First Linux CI run
- **Who:** implementer (subagent)
- **Found:** CI on HEAD 0eeaf7d failed `:mock-provider:test` with two tests that never failed on Windows.
- **Done:**
  1. `GET /__mock/stats` and `GET /__mock/requests` wait until `inFlight` is 0, at most `mock.journal.read-wait` (250 ms), then answer with what is there (`AdminHandlers.kt:105`, systems contract rule 7, [bug](../knowledge/bugs/mock-journal-entry-visible-after-the-response.md)). `JournalReadWaitIntegrationTest` holds an arrival open in the real journal, so it was red on any machine before the fix. The polls for `inFlight != 0` keep their meaning, each read arriving up to the bound later. `JournalIntegrationTest`'s reset test does two reads inside its hold, so that hold went from 600 ms to 1500 ms. No query flag was needed.
  2. `ReactorDelayTest:17` pins that plain `delay` does not resume on a `parallel-` thread, not the third-party thread name; CI saw `Test worker @coroutine#1742` where Windows saw `DefaultExecutor`, and the cause is not established ([gotcha](../knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md)).
- **Linux proof:** `./gradlew build --no-daemon --continue` in `eclipse-temurin:25-jdk` (25.0.5) from a repo snapshot, Docker socket mounted: `BUILD SUCCESSFUL`, `koverVerify` included, 0 skipped. `test`: gateway-core 41, gateway-app 55, mock-provider 310, architecture-tests 48, test-support 14; `integrationTest`: mock-provider 2, test-support 11. The first attempt failed only `integrationTest`, on `Could not connect to Ryuk at 172.17.0.1`; `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` fixed that.
- **Next:** commit (`Roadmap: FND-03`).
