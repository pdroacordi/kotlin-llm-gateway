# Agent run with tool calls

Updated: 2026-11-03 (target design, before AGT-01)

```mermaid
sequenceDiagram
    autonumber
    participant Au as Agent author
    participant AA as agent-app
    participant DB as Postgres (runs, steps)
    participant GW as gateway-app
    participant T as Tool (allowlisted host)

    Au->>AA: POST /agents/v1/agents/{id}/runs
    AA->>DB: insert run PENDING, pin agent version
    AA-->>Au: 202 + run ID
    loop until a final answer or a limit (steps, tokens, time)
        AA->>DB: insert model step PLANNED
        AA->>GW: POST /v1/chat/completions (agent team key, run and step IDs, trace context)
        GW-->>AA: assistant message (text or tool calls) + usage
        AA->>DB: model step COMPLETED
        opt tool calls
            AA->>DB: tool step PLANNED (idempotency key)
            AA->>T: HTTP call (timeout, size limit)
            T-->>AA: result
            AA->>DB: tool step COMPLETED
        end
    end
    AA->>DB: run SUCCEEDED, FAILED or LIMIT_REACHED
    Au->>AA: GET /agents/v1/runs/{id}
    AA-->>Au: run, steps, cost rollup, trace ID
```

- A step's state is written before its side effect. After a crash the executor resumes `RUNNING` runs, skips `COMPLETED` steps and re-executes `PLANNED` ones with the same idempotency key (AGT-03).
- Orchestration patterns (AGT-04) compose runs of several agents; each agent's calls still go through the gateway.
