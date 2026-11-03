---
id: gotchas/docker-desktop-publishes-ports-held-by-windows-processes
type: gotcha
title: Docker Desktop on Windows publishes 127.0.0.1:<port> even when a Windows process already listens on it, and the host port keeps reaching that process
status: active
tags: [docker, docker-desktop, windows, compose, ports, local-stack]
related: [systems/local-stack, runs/2026-11-03-smoke-local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
  - scripts/preflight.sh
---

## Summary

On Docker Desktop (29.3.1, Windows 11), publishing `127.0.0.1:<port>` succeeds when a Windows process already listens on `0.0.0.0:<port>`. No "port is already allocated" error appears, the container's in-container healthcheck passes, and `127.0.0.1:<port>` on the host still reaches the Windows process. `docker compose up --wait` therefore reports the stack healthy while one of its ports serves another program.

## Details

FND-02 smoke (`runs/2026-11-03-smoke-local-stack`, S15, finding 1), on a scratch port:

- A Python listener bound `0.0.0.0:3199` (`netstat`: `TCP 0.0.0.0:3199 0.0.0.0:0 LISTENING 19528`).
- `docker run --rm -d -p 127.0.0.1:3199:6379 redis:8.10.3-trixie` exited 0.
- A client that connected to `127.0.0.1:3199` and sent `PING` got `WINDOWS-PY` from the Python listener, not `+PONG` from Redis.
- With the stack up, `netstat -ano` showed no LISTENING socket for any published port, only client sockets. Docker Desktop forwards published ports without a Windows listener, so it has no conflict to detect.

The trigger in practice: Grafana's default was 3001, which a Next.js dev server (`0.0.0.0` and `[::]`) of another project held during the smoke. The stack would have come up healthy, and `http://127.0.0.1:3001` would have served that app. A conflict between two containers is still detected by Docker; this concerns ports held by Windows processes.

## How to handle it

- Check the host before `up`: `scripts/preflight.sh` warns, while the `llmgw` project is down, about each stack port that a process already listens on (`netstat -ano -p TCP` and `-p TCPv6` on Windows, matching rows whose remote end is `0.0.0.0:0` or `[::]:0`; `ss -ltnp` on Linux), with the PID. Verified 2026-11-03 with a listener on `0.0.0.0:3199` and `LLMGW_GRAFANA_PORT=3199`: `port 3199 (LLMGW_GRAFANA_PORT) is already in use (pid 26200 on 0.0.0.0:3199)`.
- Avoid ports dev servers commonly take: Grafana's default moved to 3301.
- After `up`, trust `tools/smoke/stack-check.sh`, which talks to each service over the host port, rather than `healthy`.
- Do not rely on `up` failing to tell you a port is taken.
