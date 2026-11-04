# Request pipeline: chat completion

Updated: 2026-11-04 (target design; GW-01 to GW-04 built the first slice)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as gateway-app
    participant A as AuthWebFilter
    participant T as TeamAuthenticator
    participant S as TeamStorePort
    participant RL as RateLimiterPort
    participant G as GuardrailPort
    participant K as ResponseCachePort
    participant R as Router
    participant P as RoutingChatModelPort
    participant D as OpenAiChatAdapter or AnthropicChatAdapter
    participant U as UsageSink

    C->>API: POST /v1/chat/completions
    API->>API: assign x-request-id (UUIDv7), validate x-client-request-id
    API->>A: withContext(RequestContext + MDC)
    A->>T: authenticate(Authorization values)
    T->>S: findKey(digest), Caffeine first
    S-->>T: KeyCredential, null or Failed
    alt missing, unknown, revoked or expired
        T-->>A: Rejected
        A-->>C: 401 missing_api_key or invalid_api_key
    else store unavailable, key not cached
        T-->>A: Rejected
        A-->>C: 503 team_store_unavailable
    end
    T-->>A: Authenticated(team)
    A-->>API: withContext(TeamContext + MDC teamId)
    API->>RL: reserve(team, estimated tokens)
    alt over limit
        RL-->>API: RateLimited
        API-->>C: 429 + retry-after
    end
    API->>G: input rules (redact PII, injection check)
    alt blocked
        G-->>API: GuardrailBlocked
        API-->>C: 400 guardrail error
    end
    API->>K: lookup(key computed after redaction)
    alt exact or semantic hit
        K-->>API: cached response
    else miss
        API->>R: route(request, deployment health, budget state)
        R-->>API: deployment + fallback chain
        API->>P: complete or stream
        P->>D: by deployment.provider
        alt member the adapter cannot honour
            D-->>API: UnsupportedByProvider
            API-->>C: 400, no upstream call
        end
        opt fails before the first byte
            API->>P: next deployment in the chain
        end
        D-->>API: response + usage
        API->>G: output rules
        API->>K: store (cacheable and allowed only)
    end
    API->>RL: reconcile(reserved, actual usage)
    API->>U: usage event (request ID, team, model, usage, cost)
    API-->>C: 200 or SSE stream + x-gateway-* headers
```

- Every error in the diagram (401, 503, 429, 400 guardrail, 400 unsupported member) is written by the one mapper, `GatewayErrorWebExceptionHandler`, as the OpenAI envelope and carries `x-request-id` (FND-04).
- Auth precedes routing and the body is never read before it, so a rejected request costs no buffering, and an unauthenticated 404, 405 or preflight under `/v1` is a 401 (ADR-GW-04-003).
- Rate limiting runs next so a rejected request costs one Redis call.
- Input guardrails run before the cache: the key is computed on the redacted prompt, and a prompt blocked today is not served from a cache filled before the rule existed.
- On a stream, output rules scan a sliding window (GRD-05), and reconciliation and the usage event happen when the stream ends or the client disconnects. A stream is cold: the provider call starts when the body is subscribed, and a failure before the first chunk is still an error status.
- Each participant is a span (OBS-01).
- Built by GW-01 to GW-04: request ID, auth, `ModelResolver`, `RoutingChatModelPort` over both adapters, `complete` and `stream` with the team on `ChatCall`, the reply, SSE with commit at the first frame. Limits, guardrails, cache and usage events are later items. Until RTE-01 `PrefixModelResolver` stands in for the router and there is no fallback chain.
- The Anthropic branch of a stream is in [sequence-anthropic-stream](sequence-anthropic-stream.md).
