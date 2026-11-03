---
id: gotchas/grafana-serves-any-host-header-unless-enforce-domain
type: gotcha
title: Grafana 13.3.0 serves API writes for any Host header, and skips its CSRF check for anonymous requests, unless enforce_domain is on
status: active
tags: [grafana, security, dns-rebinding, csrf, docker, local-stack]
related: [bugs/grafana-anonymous-admin-reachable-through-dns-rebinding, systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - deploy/compose.yaml
---

## Summary

Grafana 13.3.0 does not check the `Host` header by default (`[server] enforce_domain = false`), and its CSRF check, which compares `Origin` with `Host`, runs only when the request carries a login cookie (`[security] csrf_always_check = false`). With anonymous auth on, a request whose `Host` and `Origin` are both an attacker's hostname is served with the anonymous role. That is the shape of a DNS-rebinding request from a browser.

## Details

Reproduced on 2026-11-03 against a throwaway `grafana/grafana:13.3.0` container with `GF_AUTH_ANONYMOUS_ENABLED=true` and `GF_AUTH_ANONYMOUS_ORG_ROLE=Admin`, with nothing else set. Each request went to the container's loopback-published port:

| Request | Result |
|---|---|
| `POST /api/datasources`, `Origin: http://evil.example`, `Content-Type: text/plain`, JSON body | `400 bad request data`: Grafana binds only `application/json` bodies, so a CORS "simple request" cannot write |
| `OPTIONS /api/datasources` preflight from `http://evil.example` | `404`, no `Access-Control-*` headers: a cross-origin JSON `fetch` is blocked by the browser |
| `POST /api/datasources`, `Host: rebind.example:3001`, `Origin: http://rebind.example:3001`, `Content-Type: application/json` | `200 Datasource added` |

The third request is what a page on `rebind.example` sends after its DNS name has been rebound to `127.0.0.1`. The browser treats it as same-origin, and Grafana accepts it.

With `GF_SERVER_DOMAIN=127.0.0.1`, `GF_SERVER_ENFORCE_DOMAIN=true` and `GF_SERVER_ROOT_URL=http://127.0.0.1:3001/`, the same rebinding `POST` gets `301 Location: http://127.0.0.1:3001/api/datasources` and creates nothing. When the browser follows that redirect, the request becomes cross-origin and needs a preflight, which Grafana does not answer. A `POST` with `Host: 127.0.0.1:<port>` still gets 200. `/api/health` is served before the host check, so it answers 200 for any `Host`, and the in-container `wget http://127.0.0.1:3000/api/health` healthcheck keeps working.

Grafana configuration reference: `[server] enforce_domain` ("Redirect to correct domain if the host header does not match the domain") and `[security] csrf_always_check`.

## How to handle it

- Wherever Grafana runs with anonymous auth, even on a loopback-only port, set `GF_SERVER_ENFORCE_DOMAIN=true` with `GF_SERVER_DOMAIN` and `GF_SERVER_ROOT_URL` equal to the address users and tools open (`http://127.0.0.1:${LLMGW_GRAFANA_PORT:-3301}/` in `deploy/compose.yaml`; the default port moved from 3001 to 3301 after this entry was written).
- Binding to `127.0.0.1` keeps the LAN out, but not a browser on the same machine. Treat any unauthenticated HTTP admin surface on loopback as reachable from web pages through DNS rebinding.
- Redis is not exposed this way: it closes a connection that sends `POST` or `Host:` lines. Prometheus is safe as long as `--web.enable-admin-api` and `--web.enable-lifecycle` stay off.
