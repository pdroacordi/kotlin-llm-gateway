# Anthropic stream translation

Updated: 2026-11-04 (GW-03, built)

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (OpenAI SDK)
    participant S as ChatCompletionService
    participant R as RoutingChatModelPort
    participant A as AnthropicChatAdapter
    participant P as Anthropic API

    C->>S: POST /v1/chat/completions (stream, model claude-*)
    S->>R: stream(ChatCall, deployment anthropic)
    R->>A: stream(call)
    A->>A: AnthropicRequestWriter
    alt Refused
        A-->>S: Failed(UnsupportedByProvider)
        S-->>C: 400, no upstream call
    end
    A->>P: POST /v1/messages, x-api-key, anthropic-version
    P-->>A: message_start, ping, thinking blocks
    Note over A: Held. No chunk, nothing committed.
    alt event: error before the first chunk
        P-->>A: event: error
        A-->>S: Failed(row by error.type)
        S-->>C: real status, JSON envelope
    else text_delta or tool_use
        P-->>A: first delta
        A-->>S: Chunk (role + content or tool call)
        S-->>C: 200, first frame (commit point)
        P-->>A: deltas
        A-->>C: chunks
        P-->>A: message_delta, message_stop
        A-->>S: finish chunk with llmgw.thinking_blocks, usage chunk, Completed
        S-->>C: data: [DONE]
    end
```

- `AnthropicStreamDecoder` holds everything until the first text or tool delta (ADR-GW-03-007), so an `overloaded_error` right after `message_start` is a 503 and RTE-02 keeps its fallback window.
- An `event: error` after the first frame is one error frame, no `[DONE]` and a clean end (ADR-019).
- Tool indexes are renumbered from 0; Anthropic counts every content block (ADR-GW-03-012).
- The chunks pass through `StreamTally` and `ChatStreamFrames`, as on OpenAI. `SseStreamCall` and `StreamLifecycle` in `.gateway.providers.upstream` run the call and the end of the stream.
