---
id: gotchas/reactor-netty-flux-receive-warns-on-a-reset-during-a-write
type: gotcha
title: A client reset during a response write leaves a FluxReceive WARN with no request ID
status: active
tags: [reactor-netty, logging, disconnect]
related: [runs/2026-11-05-smoke-embeddings]
created: 2026-11-05
updated: 2026-11-05
source_files: []
---

## Summary

A client that resets the connection during a write gives one WARN, `An exception has been observed post termination`, with no request ID and no data.

## Details

Seen 20 times in the GW-05 smoke run, in the start that did 150 random disconnects. No request is at fault and no data is in the line.

## How to handle it

Do not read it as a gateway failure. It cannot be tied to a request by the log alone, which OBS-03 owns.
