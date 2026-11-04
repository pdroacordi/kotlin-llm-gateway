---
id: gotchas/tls-peer-close-during-handshake-is-a-closed-channel-exception
type: gotcha
title: A peer that closes during the TLS handshake raises a closed-channel exception, not SSLHandshakeException, through WebClient
status: active
tags: [reactor-netty, webclient, tls, errors, adapters]
related: [systems/provider-adapters, gotchas/pooled-connection-closed-without-connection-close-costs-a-502]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
---

## Summary

On Reactor Netty 1.3.8 and WebClient 7.0.10, a TCP peer that accepts, reads the ClientHello and closes gives `WebClientRequestException` with cause `io.netty.channel.StacklessClosedChannelException`. No `SSLHandshakeException` is in the chain, so `OpenAiTransportFailures` answers 502 `upstream_error` for a call that cannot have been sent.

## Details

Through the real adapter and server, against a local socket, 2026-11-04:

| Provider side | Exception | Answer |
|---|---|---|
| Closes during the handshake | `StacklessClosedChannelException` | 502 `upstream_error` |
| Certificate not trusted | `SSLHandshakeException` | 503 `provider_unavailable` |
| Plain HTTP on the TLS port | not an `SSLHandshakeException` | 502 `upstream_error` |
| Handshake good, closes after reading the request | closed before the response | 502 `upstream_error` |
| Handshake good, answers | none | 200 |

The first HTTPS call on the JVM, a request through the real server, raised no `BlockingOperationError` under BlockHound. Which thread built the SSL context was not checked.

## How to handle it

- This is the safe direction: 502 never repeats a call, 503 might. Do not widen `nothingWasSent` to `ClosedChannelException`, which a close after the write raises too.
- Read "TLS handshake" in the 503 row as a handshake that failed, not one the peer abandoned. A provider's load balancer that drains by closing during the handshake costs a 502, and RTE-02 will not fall back on it.
