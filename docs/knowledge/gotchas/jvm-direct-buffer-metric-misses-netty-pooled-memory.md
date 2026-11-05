---
id: gotchas/jvm-direct-buffer-metric-misses-netty-pooled-memory
type: gotcha
title: The JVM's direct buffer metric reads 0 while Netty holds pooled direct memory
status: active
tags: [netty, metrics, memory]
related: [runs/2026-11-05-smoke-embeddings]
created: 2026-11-05
updated: 2026-11-05
source_files: []
---

## Summary

`jvm.buffer.memory.used{id=direct}` read 0 throughout 33 MB embedding replies, so it cannot report the peak direct memory of a reply.

## Details

Seen in the GW-05 smoke run on Netty 4.2.19, Java 25, Windows. NMT `Other` peaked at 91,407 KB over replies of 35 MB at the same time. Why Netty's pool is outside that JVM pool was not traced.

## How to handle it

Read direct memory from NMT (`-XX:NativeMemoryTracking=summary`) or from Netty's own allocator metrics.
