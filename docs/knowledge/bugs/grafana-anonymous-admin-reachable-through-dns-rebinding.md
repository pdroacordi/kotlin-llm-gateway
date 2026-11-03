---
id: bugs/grafana-anonymous-admin-reachable-through-dns-rebinding
type: bug
title: The dev stack's anonymous-Admin Grafana accepts DNS-rebinding requests, so any web page the developer opens can administer it
status: active
severity: medium
tags: [grafana, security, dns-rebinding, ssrf, compose, local-stack]
related: [gotchas/grafana-serves-any-host-header-unless-enforce-domain, systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
  - tools/smoke/stack-check.sh
---

## Summary

`deploy/compose.yaml` ran Grafana with anonymous `Admin` on `127.0.0.1:3001` (the default port then). ADR-FND-02-003 treated loopback as safe, but Grafana serves API writes for any `Host` header. A web page that rebinds its DNS name to `127.0.0.1` can therefore act as Grafana Admin from the developer's browser.

## Symptom

Found in the FND-02 review (2026-11-03) and reproduced on a throwaway `grafana/grafana:13.3.0` container with the compose file's `GF_AUTH_*` settings:

- `POST /api/datasources` with `Host: rebind.example:3001`, `Origin: http://rebind.example:3001` and a JSON body returned `200 Datasource added`.
- Cross-origin attempts without rebinding were refused: a `text/plain` body got `400 bad request data`, and the preflight got 404 with no CORS headers.

What an Admin session allows: create datasources and query through them. That includes a PostgreSQL datasource at `postgres:5432` with the documented `llmgw`/`llmgw` credentials (arbitrary SQL on the dev database), and HTTP datasources pointing at anything the Grafana container can reach, `host.docker.internal` and the apps on the host included (SSRF). It also allows changing org settings and users. Nothing is reachable from the LAN; the path is a browser on the same machine.

## Root cause

`deploy/compose.yaml:147-149` set `GF_AUTH_ANONYMOUS_ENABLED=true`, `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin` and `GF_AUTH_DISABLE_LOGIN_FORM=true`, but no `GF_SERVER_ENFORCE_DOMAIN`. With `enforce_domain` off, Grafana 13.3.0 never validates `Host`. Its CSRF check runs only for requests that carry a login cookie, and anonymous requests carry none ([gotcha](../gotchas/grafana-serves-any-host-header-unless-enforce-domain.md)).

## Fix

Fixed in FND-02 after review:

- `deploy/compose.yaml:155-157` sets `GF_SERVER_DOMAIN: 127.0.0.1`, `GF_SERVER_ENFORCE_DOMAIN: "true"` and `GF_SERVER_ROOT_URL: http://127.0.0.1:${LLMGW_GRAFANA_PORT:-3301}/` (comment at `:152-154`). Grafana now redirects a request whose `Host` is not `127.0.0.1` instead of serving it. The default host port moved from 3001 to 3301 in the same round, for an unrelated reason ([gotcha](../gotchas/docker-desktop-publishes-ports-held-by-windows-processes.md)).
- ADR-FND-02-003 and the systems entry say "anonymous Admin on loopback, with the host enforced" (spec revision line, systems entry contract rule 7).

Verified: on a throwaway Grafana without the settings, the rebinding `POST` got 200 and created a datasource; with them it got `301` to `http://127.0.0.1:<port>/api/datasources` and created nothing. On the real stack, `127.0.0.1:3301` still answers 200, `localhost:3301` is redirected to `127.0.0.1:3301`, the `wget http://127.0.0.1:3000/api/health` healthcheck passes, and `stack-check.sh` passed 12/12.

Commit: the FND-02 commit (`Roadmap: FND-02`).

## Prevention

Added for FND-02:

- `tools/smoke/stack-check.sh` check `grafana-host` (`check_grafana_host`, `:265-283`, called at `:296`): a `POST /api/datasources` with `Host: rebind.example` must not get 2xx and must create no datasource; one it did create is deleted again. It runs in every `gateway-smoke` run and in CI (`stack.yml`). Red on the unfixed container, green after.
- Systems entry contract rule 7: Grafana's anonymous Admin answers only `Host: 127.0.0.1`.

Review rule, kept: an unauthenticated HTTP admin UI on a loopback port is reachable from web pages through DNS rebinding, so it must validate `Host` ([gotcha](../gotchas/grafana-serves-any-host-header-unless-enforce-domain.md)).
