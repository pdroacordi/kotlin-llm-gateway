---
id: gotchas/pooled-connection-closed-without-connection-close-costs-a-502
type: gotcha
title: A provider that closes a pooled connection without Connection close costs the next request on it a 502, and disableRetry means nothing repeats it
status: active
tags: [adapters, reactor-netty, webclient, pool, retries, errors]
related: [systems/provider-adapters, runs/2026-11-04-smoke-sse-streaming]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiChatAdapter.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
  - gateway-app/src/main/resources/application.yaml
---

## Summary

On Reactor Netty 1.3.8 with `disableRetry(true)`, a request that lands on a pooled connection the provider has just closed fails, and is not repeated. The client gets a 502 `upstream_error` for a request the provider never received.

## Details

Seen by accident in smoke R4 ([run](../runs/2026-11-04-smoke-sse-streaming.md), Revision). A fake provider closed 0.5 s after a complete response, without `Connection: close`. The next stream on that connection failed with `status=null exception=WebClientRequestException durationMs=511`, 3 of 14 requests. The fake counted 11 requests of 14, so it never saw those three.

- This is the correct row. The exception has no "nothing sent" cause in its chain, so `OpenAiTransportFailures` cannot tell this case from a call the provider may have billed, and it answers `UpstreamError`.
- Only a provider that closes inside `max-idle-time` hits it, since the pool evicts an idle connection after that.
- Real keep-alive behaviour of a provider is unproven: no `live` call was made.

## How to handle it

- Keep `disableRetry(true)` (`OpenAiChatAdapter.kt:82`): a call the provider may have billed is never repeated, and retries are RTE-02's.
- Keep `pool.max-idle-time` below the provider's keep-alive (`application.yaml:48`, read at `OpenAiChatAdapter.kt:72`). Once a live call shows a real figure, set it from that.
- RTE-02 must not treat this 502 as safe to repeat: only `ProviderUnavailable` means nothing was sent (`OpenAiTransportFailures.kt:47-53`).
- A fake provider in a test sends `Connection: close`, or the next request fails on the wrong path, as the first version of the smoke's did. `RawProviderSocketIntegrationTest.kt:118` and `RawProviderStreamIntegrationTest.kt:286` do.
