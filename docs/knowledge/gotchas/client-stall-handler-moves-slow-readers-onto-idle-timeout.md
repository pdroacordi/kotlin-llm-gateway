---
id: gotchas/client-stall-handler-moves-slow-readers-onto-idle-timeout
type: gotcha
title: A stall handler that spares a slow reader hands it to idle-timeout, which ends the stream with an upstream_timeout that blames a healthy provider
status: active
tags: [streaming, back-pressure, timeouts, reactor, netty, logging]
related: [systems/provider-adapters, gotchas/reactor-flux-timeout-takes-publishers-and-ignores-demand, bugs/client-write-timeout-cuts-a-slow-client-that-is-still-reading, runs/2026-11-04-smoke-sse-streaming]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/web/ClientStallHandler.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/ChatStreamLog.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatStream.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/StreamDeadlines.kt
---

## Summary

`ClientStallHandler` keeps a slow reader's connection, but Reactor's `Flux.timeout` cannot see demand, so `idle-timeout` then ends the stream. "A reader is safe down to one kernel step per timeout" holds for the connection only. Seen on Reactor 3.8.8, Reactor Netty 1.3.8 and Netty 4.2.19, on Windows loopback.

## Details

Smoke R1 ([run](../runs/2026-11-04-smoke-sse-streaming.md), Revision): 16 KB frames, 500 pieces, the mock at 0 ms latency, defaults (`client-write-timeout` 30 s, `idle-timeout` 60 s), clients reading steadily.

| Rate, KB/s | Client | Server |
|---|---|---|
| 39, 19.7 | Complete, 500 of 500 | `completed` |
| 11.8 | `upstream_timeout` frame at 152 tokens, seen at 204 s | Failed at 71 s |
| 7.9 | Same, seen at 305 s | Failed at 106 s |
| 6.0 | Same, seen at 405 s | Failed at 141 s |
| 4.0, 3.0 | Still reading at 470 s, frame queued | Failed at 211 s, 281 s |
| 2.9, 1.9, 0.9 | Stall cut, EOF, no frame | `cancelled` |

- The edge is a bracket, 11.8 to 19.7 KB/s, not a point. Inferred, not measured: the demand refill of about 64 frames, 1 MB, must drain inside `idle-timeout`, so the edge scales with frame size.
- It moves with `idle-timeout`. At idle 2 s and a 1 s write timeout the bracket is 205 to 683 KB/s: 146 KB/s got the frame on 3 of 3 clients, while at idle 60 s and the same write timeout 85.6 KB/s completed. So "160 KB/s held" is true only with the larger idle.
- A healthy provider gets the blame three times: the client's `upstream_timeout` frame, ERROR `Stream failed ... code=upstream_timeout` (`ChatStreamLog.kt:41`), and WARN `Provider stream failed ... TimeoutException` (`OpenAiChatStream.kt:136`).
- The client learns late, behind the backlog: 199 s after the server at 7.9 KB/s. The upstream slot is free meanwhile (`client_cancelled`).
- Ordinary frames of about 250 bytes were not affected: the provider finished first. Those clients meet the stall limit instead, because a client below the provider's rate builds a backlog too: a 984 B/s client on a 13 KB/s stream had 40888 bytes pending when it was cut at 76.8 s, and 2.9 KB/s completed.

## How to handle it

- State both limits when describing a slow reader: the connection's (`ClientStallHandler.kt:74`) and the stream's (`OpenAiChatStream.kt:103-106`, `StreamDeadlines.kt:16`).
- Never read an `upstream_timeout` after commit as proof of a sick provider: RTE-02's breaker must not count it. `StreamStarvedUpstreamTest` pins the behaviour.
- The fix is GW-08: an idle timer that does not count time the client, not the provider, holds the stream.
- Every rate above is a Windows loopback number, and socket buffers set both limits, so measure again elsewhere.
