# Glossary

Domain terms, alphabetical, with the code identifier each maps to. Identifiers are targets until the item that introduces them lands; that item updates the row if the name changes.

| Term | Meaning | Code identifier |
|---|---|---|
| Agent | A registered definition of an LLM-driven worker: model alias, instructions, tools, limits. | `AgentDefinition` |
| Agent version | An immutable snapshot of an agent definition. A run pins one version. | `AgentVersion` |
| API key | The secret a team presents on every request. Stored as an HMAC, shown once at creation. | `ApiKey` |
| Budget | A spend limit for a team over a period. Soft: alert. Hard: reject or downgrade (RTE-04). | `Budget` |
| Cache hit (exact) | A response served because a normalized request with the same key was stored before. | `CacheOutcome.EXACT_HIT` |
| Cache hit (semantic) | A response served because a stored request is similar enough (above the threshold) in embedding space. | `CacheOutcome.SEMANTIC_HIT` |
| Client request ID | A client's own correlation ID, sent in `x-client-request-id` (1 to 512 printable ASCII characters), echoed and logged; never used as the request ID. | `ClientRequestId` |
| Circuit breaker | Per-deployment switch that stops sending traffic after repeated failures and probes before closing again. | `DeploymentHealth` |
| Deployment | A concrete target a route can use: provider, provider model name, endpoint, credentials reference. | `Deployment` |
| Error envelope | The OpenAI error body every gateway error uses: `{"error":{"message","type","param","code"}}`. Expected failures are `GatewayError` variants; one table maps each to status, `type` and `code`. | `GatewayError`, `ErrorReply` |
| Fail-open / fail-closed | What a stage does when its own dependency fails: let the request through, or reject it. Declared per rule. | `FailureMode` |
| Fallback chain | Ordered deployments tried for one request, only before the first byte reaches the client. | `FallbackChain` |
| Guardrail | A rule applied to input or output, with a verdict: allow, redact or block. | `GuardrailRule`, `GuardrailVerdict` |
| Ledger | Append-only record of the cost of every call, written from usage events. | `LedgerEntry` |
| Local stack | The seven development services in `deploy/compose.yaml` (Postgres, Redis, Redpanda, OTel collector, Jaeger, Prometheus, Grafana) on loopback ports from `LLMGW_*_PORT`; project `llmgw` unless `COMPOSE_PROJECT_NAME` says otherwise. | `deploy/compose.yaml` |
| Mock provider | The deterministic provider fake on port 8090 that every test and smoke run calls instead of a real provider; OpenAI and Anthropic shapes, scenarios and a request journal under `/__mock`. | `MockProviderApplication` |
| Model alias | The model name a client sends (`chat-default`), resolved by the team's route table. | `ModelAlias` |
| Price catalog | Per-model prices for input, output and cached-input tokens, with effective dates. | `PriceCatalog` |
| Provider | An upstream LLM API vendor (OpenAI, Anthropic, a local server). | `Provider` |
| Rate limit | A cap on requests or tokens per time window for a team. | `RateLimitPolicy` |
| Request ID | The gateway-generated UUIDv7 for one call, returned in `x-request-id`; the key that ties a response, its log lines, its span and its ledger row. Never the trace ID. | `RequestId` |
| Request journal | The mock provider's bounded, redacted log of the requests it received, with its arrival counter (`lastSeq`) and per-route, per-model counts; read over `/__mock/requests` and `/__mock/stats`. | `RequestJournal` |
| Reservation | Tokens held against a team's limit before a call and reconciled with real usage after it. | `TokenReservation` |
| Route table | A team's mapping from model aliases to deployments and the routing policy. | `RouteTable` |
| Run | One execution of an agent version on an input. | `Run` |
| Scenario | Per-model behaviour of the mock provider: ordered steps (each consumed by `times` requests) and then an `otherwise` behaviour (latency, error, fault, content, usage), set over `/__mock/scenarios`; `*` serves every model without its own. | `Scenario`, `Behaviour` |
| Shared container | One Testcontainers container per test JVM for a backing service, started on first use and removed by Ryuk when the JVM ends; never stopped by a test or a Spring context. | `SharedPostgres`, `SharedRedis`, `SharedRedpanda`, `SharedMockProvider` |
| Stack check | The script that checks a running local stack: services, health, loopback ports, OTLP traces to Jaeger and metrics to Prometheus, Prometheus targets, Grafana datasources; one `PASS` or `FAIL` line per check. | `tools/smoke/stack-check.sh` |
| Step | One unit inside a run: a model call, a tool call or an approval. | `Step` |
| Supervisor | Orchestration pattern where one agent decides which agent acts next. | `SupervisorPattern` |
| Team | The tenant: owns keys, limits, budgets, routes and policies. Every request resolves to one. | `Team` |
| Tool | A registered HTTP capability an agent can call, described by a JSON schema, on an allowlisted host. | `ToolDefinition` |
| TTFT | Time to first token: from request receipt to the first streamed chunk sent. | `ttft` metric |
| Usage | Provider-reported token counts (input, output, cached input), or an estimate flagged as such. | `Usage` |
| Usage event | The message emitted once per call with team, model, usage and cost, keyed by request ID. | `UsageEvent` |
| Workflow | A declared orchestration of agents: sequential, parallel fan-out and fan-in, or supervisor. | `Workflow` |
