# Glossary

Domain terms, alphabetical, with the code identifier each maps to. Identifiers are targets until the item that introduces them lands; that item updates the row if the name changes.

| Term | Meaning | Code identifier |
|---|---|---|
| Admin key | The one operator credential, from `LLMGW_ADMIN_KEY`, that opens `/admin`. Never valid on `/v1`. | `OperatorAuthenticator`, `AuthProperties.adminKey` |
| Agent | A registered definition of an LLM-driven worker: model alias, instructions, tools, limits. | `AgentDefinition` |
| Agent version | An immutable snapshot of an agent definition. A run pins one version. | `AgentVersion` |
| API key | Ambiguous, so the code avoids it: a client presents a team key, and an adapter presents a provider key. The class `ApiKey` is only the provider key. | see Team key, Provider key |
| Budget | A spend limit for a team over a period. Soft: alert. Hard: reject or downgrade (RTE-04). | `Budget` |
| Cache hit (exact) | A response served because a normalized request with the same key was stored before. | `CacheOutcome.EXACT_HIT` |
| Cache hit (semantic) | A response served because a stored request is similar enough (above the threshold) in embedding space. | `CacheOutcome.SEMANTIC_HIT` |
| Chat call | One provider call with its request ID, team, deployment and request. | `ChatCall` |
| Chat chunk | One streamed delta, translated from the provider's event. | `ChatChunk` |
| Circuit breaker | Per-deployment switch that stops sending traffic after repeated failures and probes before closing again. | `DeploymentHealth` |
| Client request ID | A client's own correlation ID, sent in `x-client-request-id` (1 to 512 printable ASCII characters), echoed and logged; never used as the request ID. | `ClientRequestId` |
| Commit point | The first frame of a stream: the last moment a status or header can change. | `ChatStreamEvent.Started` |
| Conversation check | The mock provider's rule that a user message right after an assistant `tool_use` starts with `tool_result` blocks covering every `tool_use` ID, else a 400, so a wrongly translated conversation fails a test. | `AnthropicRequests` |
| Deployment | A concrete target a route can use: provider, provider model name, endpoint, credentials reference. | `Deployment` |
| Error envelope | The OpenAI error body every gateway error uses: `{"error":{"message","type","param","code"}}`. Expected failures are `GatewayError` variants; one table maps each to status, `type` and `code`. | `GatewayError`, `ErrorReply` |
| Extension member | `llmgw`: the one member the gateway adds to OpenAI's shape, on messages, deltas and stream chunks. Never forwarded to a provider except as a translation of what it holds. | `ChatExtensions.MEMBER` |
| Extras | Members the gateway does not model, carried verbatim beside the typed fields at each level of a request or completion. | `extras`, `JsonValue` |
| Fail-open / fail-closed | What a stage does when its own dependency fails: let the request through, or reject it. Declared per rule. | `FailureMode` |
| Fallback chain | Ordered deployments tried for one request, only before the first byte reaches the client (before `Started`). | `FallbackChain` |
| Guardrail | A rule applied to input or output, with a verdict: allow, redact or block. | `GuardrailRule`, `GuardrailVerdict` |
| Key digest | The HMAC-SHA-256 of a team key, 32 bytes: the only form stored, and the lookup key of the store and its cache. Never in a response or a log. | `KeyDigest`, `KeyHasher` |
| Key hint | The first 10 characters of a team key, `llmgw_` and 4 secret ones, shown in lists so a person can tell keys apart. | `TeamKey.hint`, `key_hint` |
| Ledger | Append-only record of the cost of every call, written from usage events. | `LedgerEntry` |
| Local stack | The seven development services in `deploy/compose.yaml` (Postgres, Redis, Redpanda, OTel collector, Jaeger, Prometheus, Grafana) on loopback ports from `LLMGW_*_PORT`; project `llmgw` unless `COMPOSE_PROJECT_NAME` says otherwise. | `deploy/compose.yaml` |
| Member policy | The rule an adapter holds, as data, for each member of the OpenAI request when its provider has another shape: translate, forward, ignore, ignore a default value, or reject. An unlisted member is rejected. | `AnthropicMemberPolicy`, `MemberRule` |
| Mock provider | The deterministic provider fake on port 8090 that every test and smoke run calls instead of a real provider; OpenAI and Anthropic shapes, scenarios and a request journal under `/__mock`. | `MockProviderApplication` |
| Model alias | The model name a client sends (`chat-default`), resolved by the team's route table. | `ModelAlias` |
| Model prefix | A configured model-name prefix that sends a model to a provider until RTE-01 replaces it (`claude-`, `mock-claude`). | `ModelPrefix`, `PrefixModelResolver` |
| Model resolver | Maps the model a client sent to a deployment: by model prefix, else the default provider, until RTE-01. | `ModelResolver` |
| Piece | One unit of a mock provider stream, the one `perTokenMs` and `afterPieces` count: one or more frames, each flushed alone. | `Piece` |
| Plan part | One part of the mock provider's planned reply, rendered per provider: thinking, text or a tool call. | `PlanPart`, `CompletionPlan` |
| Price catalog | Per-model prices for input, output and cached-input tokens, with effective dates. | `PriceCatalog` |
| Provider | An upstream LLM API vendor (OpenAI, Anthropic, a local server). | `Provider` |
| Provider failure | The sealed failures a provider call can end in, each one error row. | `ProviderFailure` |
| Provider key | The credential an adapter presents to a provider, from the environment. | `ApiKey` |
| Provider selection | Picking the adapter for a deployment: the resolver names the provider, and the routing port delegates to its adapter. A provider with no adapter is `model_not_found`. | `RoutingChatModelPort`, `ProviderSelectionProperties` |
| Published secret | A value of `application-local.yaml`, public by design, refused as the HMAC secret or admin key past loopback. | `PublishedSecrets` |
| Rate limit | A cap on requests or tokens per time window for a team. | `RateLimitPolicy` |
| Request ID | The gateway-generated UUIDv7 for one call, returned in `x-request-id`; the key that ties a response, its log lines, its span and its ledger row. Never the trace ID. | `RequestId` |
| Request journal | The mock provider's bounded, redacted log of the requests it received, with its arrival counter (`lastSeq`) and per-route, per-model counts; read over `/__mock/requests` and `/__mock/stats`. | `RequestJournal` |
| Reservation | Tokens held against a team's limit before a call and reconciled with real usage after it. | `TokenReservation` |
| Route table | A team's mapping from model aliases to deployments and the routing policy. | `RouteTable` |
| Run | One execution of an agent version on an input. | `Run` |
| Scenario | Per-model behaviour of the mock provider: ordered steps (each consumed by `times` requests) and then an `otherwise` behaviour (latency, error, fault, content, usage), set over `/__mock/scenarios`; `*` serves every model without its own. | `Scenario`, `Behaviour` |
| Scripted tool call | A tool call a mock scenario declares in `content.toolCalls`, rendered in each provider's shape on both forms. | `ToolCallSpec`, `PlanPart.ToolCall` |
| Shared container | One Testcontainers container per test JVM for a backing service, started on first use and removed by Ryuk when the JVM ends; never stopped by a test or a Spring context. | `SharedPostgres`, `SharedRedis`, `SharedRedpanda`, `SharedMockProvider` |
| Stack check | The script that checks a running local stack: services, health, loopback ports, OTLP traces to Jaeger and metrics to Prometheus, Prometheus targets, Grafana datasources; one `PASS` or `FAIL` line per check. | `tools/smoke/stack-check.sh` |
| Step | One unit inside a run: a model call, a tool call or an approval. | `Step` |
| Stream decoder | The provider's part of a stream: one upstream SSE event to chunks and a state, with no threads or timers. | `StreamDecoder`, `StreamLifecycle` |
| Stream event | The sealed values of a stream, ending in one terminal event. | `ProviderStreamEvent`, `ChatStreamEvent` |
| Stream summary | How a stream ended and its usage, reported once. | `StreamSummary` |
| Supervisor | Orchestration pattern where one agent decides which agent acts next. | `SupervisorPattern` |
| Team | The tenant: owns keys, limits, budgets, routes and policies. Every request resolves to one. | `Team` |
| Team context | The coroutine element holding the team, present only on an authenticated `/v1` request, so its presence is the proof. | `TeamContext` |
| Team key | The secret a team presents on every `/v1` request: `llmgw_` plus 43 base64url characters. Stored as a key digest, shown once at creation. | `TeamKeySecret`, `TeamKey` |
| Team store | The port for teams and keys: Postgres behind a Caffeine cache, the source of truth for who may call. | `TeamStorePort`, `PostgresTeamStore`, `CachingTeamStore` |
| Thinking block | A provider reasoning block with an opaque signature, never put in `content`. A reply carries it in `llmgw.thinking_blocks`; a client echoes it on the assistant message in a tool loop. | `HeldThinking` |
| Tool | A registered HTTP capability an agent can call, described by a JSON schema, on an allowlisted host. | `ToolDefinition` |
| TTFT | Time to first token: from request receipt to the first streamed chunk sent. | `ttftMs` in the end-of-stream line, until OBS-02's `ttft` metric |
| Usage | Provider-reported token counts (input, output, cached input), or an estimate flagged as such; `estimated` when the provider omitted it. | `Usage` |
| Usage event | The message emitted once per call with team, model, usage and cost, keyed by request ID. | `UsageEvent` |
| Workflow | A declared orchestration of agents: sequential, parallel fan-out and fan-in, or supervisor. | `Workflow` |
