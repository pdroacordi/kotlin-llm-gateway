# Container overview

Updated: 2026-11-04 (target design; FND-03 note; FND-02 local stack; GW-03 provider selection; GW-04 auth and Postgres)

```mermaid
flowchart LR
    clients["Client apps<br/>(OpenAI SDKs)"] -->|"/v1/*<br/>team key"| gw["gateway-app"]
    cli["llmgw CLI"] -->|"/v1/*, /admin/v1"| gw
    cli -->|"/agents/v1"| ag
    authors["Agent authors"] -->|"/agents/v1"| ag["agent-app"]
    platform["Platform team"] -->|"/admin/v1"| gw
    ag -->|"/v1/chat/completions<br/>agent team key"| gw
    ag --> agdb[("Postgres<br/>agents, runs, steps")]
    gw --> redis[("Redis<br/>limits, exact cache")]
    gw --> gwdb[("Postgres<br/>teams, keys, routes,<br/>prices, ledger")]
    gw -->|"usage events"| bus[["Kafka-compatible broker<br/>(ADR-010)"]]
    bus --> ledger["ledger writer"]
    ledger --> gwdb
    gw --> openai["OpenAI API"]
    gw --> anthropic["Anthropic API"]
    gw -.->|"dev and tests"| mock["mock-provider"]
    gw -->|OTLP| otel["OTel collector"]
    ag -->|OTLP| otel
    otel --> jaeger["Jaeger"]
    otel --> prom["Prometheus"]
    prom --> grafana["Grafana"]
    grafana -.->|datasource| jaeger
```

- The agent runtime reaches models only through the gateway (ADR-011).
- `gateway-app` reaches each provider through its own adapter and connection pool, chosen by `RoutingChatModelPort`. OpenAI is on by default, Anthropic off unless enabled (GW-03).
- `mock-provider` (`:8090`) answers the OpenAI and Anthropic paths with scripted tool calls on both, and exposes `/__mock` for scenarios, counters and the request journal (FND-03, GW-03).
- Built on the Postgres edge: `teams` and `team_keys` ([er-teams-and-keys](er-teams-and-keys.md)), over R2DBC with Flyway at startup (GW-04). Routes, prices and the ledger are later items. `gateway-app` needs Postgres to start, in `local` too.
- `/v1/*` and `/admin/v1` are authenticated by `AuthWebFilter`, with a team key and the admin key (GW-04). The modules are in [container-modules](container-modules.md).
- In development all infrastructure runs from `deploy/compose.yaml` on loopback ports (CLAUDE.md); the apps and the mock run on the host (FND-02).
- The semantic-cache vector store (ADR-009) and the broker choice (ADR-010) are open; the diagram changes when CACHE-02 and COST-02 settle them.
