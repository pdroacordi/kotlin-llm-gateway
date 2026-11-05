---
id: gotchas/netty-copies-a-heap-buffer-to-direct-on-write
type: gotcha
title: Netty copies a whole heap buffer into a new direct buffer on the event loop when it is written to a socket
status: active
tags: [netty, memory, event-loop]
related: [bugs/embedding-reply-write-copies-whole-reply-on-the-event-loop]
created: 2026-11-04
updated: 2026-11-04
source_files: []
---

## Summary

On Netty 4.2.19 `AbstractNioByteChannel.filterOutboundMessage` passes a direct `ByteBuf` through and sends any other to `newDirectBuffer`, which with a pooled allocator allocates the full size and copies. Wrapping a `byte[]` for a write is therefore not zero-copy.

## Details

Read from the bytecode of `netty-transport-4.2.19.Final.jar`. The filter runs inside `AbstractChannel`'s write, on the channel's event loop, whichever thread asked for the write. Whether the epoll transport does the same was not checked here.

## How to handle it

Write a large body as bounded slices, so one copy and one direct allocation are one slice. Count N of direct memory for a body sent as one buffer.
