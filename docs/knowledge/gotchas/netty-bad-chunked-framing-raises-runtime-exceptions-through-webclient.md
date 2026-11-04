---
id: gotchas/netty-bad-chunked-framing-raises-runtime-exceptions-through-webclient
type: gotcha
title: Broken chunked framing reaches a WebClient body as NumberFormatException or TooLongHttpLineException, not as an IOException
status: active
tags: [netty, reactor-netty, webclient, errors]
related: [bugs/adapter-broken-chunked-framing-raised-as-a-bug]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/openai/OpenAiTransportFailures.kt
---

## Summary

On Netty 4.2.19, Reactor Netty 1.3.8 and WebClient 7.0.10, a malformed chunked response errors the body with the decoder's own runtime exception, bare and at the top level. A list of transport exceptions built on `IOException` misses it.

## Details

Raw socket, `Transfer-Encoding: chunked`, read through `toDataBuffers()` and `toEntity`:

| Bytes where a chunk size belongs | Raised |
|---|---|
| `ZZZ` | `java.lang.NumberFormatException: Invalid character in chunk size` |
| 20 000 bytes with no line break | `io.netty.handler.codec.http.TooLongHttpLineException: An HTTP line is larger than 4096 bytes.` |

Neither message quotes the input. A connection closed before the terminating chunk is the `IOException` you would expect (`PrematureCloseException`).

## How to handle it

Classify by where an error came from, not by its type: anything raised above the first operator of yours on a body is a transport failure. Map it right on the body flux; `bodyFailuresAsTransport` in `OpenAiTransportFailures.kt` does so ([bug](../bugs/adapter-broken-chunked-framing-raised-as-a-bug.md)).
