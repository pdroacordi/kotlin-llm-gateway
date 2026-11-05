---
id: gotchas/reactor-netty-send-writes-its-whole-prefetch-past-unwritable
type: gotcha
title: Reactor Netty's send of a Flux keeps writing up to 128 items while the channel is unwritable
status: active
tags: [reactor-netty, netty, backpressure, memory, event-loop]
related: [gotchas/netty-copies-a-heap-buffer-to-direct-on-write, bugs/embedding-reply-write-copies-whole-reply-on-the-event-loop]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/ReplyWritePacer.kt
---

## Summary

On reactor-netty-core 1.3.8, `MonoSendMany.SendManyInner.run` uses `Channel.isWritable()` only to decide when to flush. Its loop ends on `requested` or an empty queue, so a `Flux` given to `send` or `writeWith` reaches the channel up to `reactor.netty.send.maxPrefetchSize` (default 128) items ahead of the socket.

## Details

Read from the bytecode. `requested` starts at 128, falls by one in `trySuccess` when a write completes, and refills to 128 at `REFILL_SIZE`. Items written past unwritable sit in `ChannelOutboundBuffer`; a heap buffer has already been copied to direct memory by then. `ReactorServerHttpResponse.writeWithInternal` calls `response.send`, so a multi-item `writeWith` takes this path.

## How to handle it

For large items, bound in-flight writes with `writeAndFlushWith` and one `Mono` a group, so the next group waits for the last write. The system property is global, so it would change every route.

## Revision (2026-11-05)

The advice above is wrong. `writeAndFlushWith` does not bound in-flight writes: `NettyOutbound.sendGroups` is `send(Flux.from(groups).concatMap(g -> Flux.from(g).concatWith(Mono.just(BOUNDARY)), 32), PREDICATE_GROUP_FLUSH)`, so a group is a flush boundary inside the same `MonoSendMany`, and the next group does not wait. Read from the bytecode of 1.3.8, and shown: a client that read nothing still had a whole 32 MiB reply written.

Demand cannot help, as `MonoSendMany` asks for its whole prefetch at once. Pace the source: hold each item until the channel is writable, from `channelWritabilityChanged`, and run the source on the channel's event loop, since `trySchedule` writes inline there and the next check then sees the bytes. `ReplyWritePacer` does this. Off the loop the source races ahead of the write, and a check of `isWritable` passes every time.
