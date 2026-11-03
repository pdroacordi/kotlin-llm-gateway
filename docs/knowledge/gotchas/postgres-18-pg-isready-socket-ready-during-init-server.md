---
id: gotchas/postgres-18-pg-isready-socket-ready-during-init-server
type: gotcha
title: In the postgres 18 image, pg_isready over the Unix socket reports ready while the entrypoint's temporary init server runs; over -h 127.0.0.1 it does not
status: active
tags: [postgres, docker, healthcheck, compose, local-stack]
related: [systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
---

## Summary

On a first start, the official `postgres:18.6-trixie` entrypoint runs a temporary server that listens only on the Unix socket. That server runs `initdb` and the `/docker-entrypoint-initdb.d` scripts, then stops, and the real server starts on TCP. A socket `pg_isready` (no `-h`) exits 0 during that window, so a healthcheck written that way reports healthy before init is done, and again in the gap between the two servers.

## Details

Reproduced on 2026-11-03: a throwaway container with an init script running `sleep 8`, polled every ~0.7 s with `docker exec`:

```
2  socket[/var/run/postgresql:5432 - accepting connections rc=0]  tcp[127.0.0.1:5432 - no response rc=2]
...
10 socket[... accepting connections rc=0]                          tcp[... no response rc=2]
11 socket[... accepting connections rc=0]                          tcp[... accepting connections rc=0]
```

The container log shows the order: `listening on Unix socket` (temporary server), `running /docker-entrypoint-initdb.d/10-sleep.sh`, `database system is shut down`, then `listening on IPv4 address "0.0.0.0", port 5432` (real server). The entrypoint starts the temporary server with `listen_addresses=''`.

## How to handle it

- Healthcheck over TCP: `pg_isready -h 127.0.0.1 -U <user> -d <db>` (`deploy/compose.yaml:34`). It is ready only once the real server listens.
- Testcontainers' `PostgreSQLContainer` waits for the log line "database system is ready to accept connections" twice, once per server, for the same reason.
