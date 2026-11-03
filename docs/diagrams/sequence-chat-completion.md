# Request pipeline: chat completion

Updated: 2026-11-03 (target design, before GW-01)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as gateway-app
    participant A as Auth
    participant RL as RateLimiterPort
    participant G as GuardrailPort
    participant K as ResponseCachePort
    participant R as Router
    participant P as ChatModelPort
    participant U as UsageSink

    C->>API: POST /v1/chat/completions
    API->>API: assign x-request-id (UUIDv7), validate x-client-request-id
    API->>A: resolve API key
    A-->>API: team
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
        opt fails before the first byte
            API->>P: next deployment in the chain
        end
        P-->>API: response + usage
        API->>G: output rules
        API->>K: store (cacheable and allowed only)
    end
    API->>RL: reconcile(reserved, actual usage)
    API->>U: usage event (request ID, team, model, usage, cost)
    API-->>C: 200 or SSE stream + x-gateway-* headers
```

- Every error in the diagram (429, 400 guardrail) is written by the one mapper, `GatewayErrorWebExceptionHandler`, as the OpenAI envelope and carries `x-request-id` (FND-04).
- Rate limiting runs first so a rejected request costs one Redis call.
- Input guardrails run before the cache: the key is computed on the redacted prompt, and a prompt blocked today is not served from a cache filled before the rule existed.
- On a stream, output rules scan a sliding window (GRD-05), and reconciliation and the usage event happen when the stream ends or the client disconnects.
- Each participant is a span (OBS-01).
