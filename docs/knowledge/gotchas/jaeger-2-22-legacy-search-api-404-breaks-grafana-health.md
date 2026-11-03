---
id: gotchas/jaeger-2-22-legacy-search-api-404-breaks-grafana-health
type: gotcha
title: Jaeger 2.22.0 answers the legacy /api/services and /api/traces?service= search with 404, which fails Grafana 13.3.0's Jaeger datasource health check
status: active
tags: [jaeger, grafana, tracing, local-stack, smoke]
related: [systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - tools/smoke/stack-check.sh
  - .claude/skills/gateway-smoke/SKILL.md
---

## Summary

`jaegertracing/jaeger:2.22.0` (v2 all-in-one) no longer serves the legacy search endpoints of its query API. Lookup by trace ID and the v3 API still work. Grafana 13.3.0's Jaeger datasource health check calls `/api/services`, so it reports an error against a working Jaeger.

## Details

Reproduced on 2026-11-03 with throwaway containers, `jaegertracing/jaeger:2.22.0` (`JAEGER_LISTEN_HOST=0.0.0.0`) and `grafana/grafana:13.3.0`:

| Request to Jaeger `:16686` | Status | Body |
|---|---|---|
| `/api/services` | 404 | `404 page not found` (no route) |
| `/api/traces?service=x` | 404 | `404 page not found` (no route) |
| `/api/traces/<32 hex>` (unknown ID) | 404 | `{"data":null,...,"errors":[{"code":404,"msg":"trace not found"}]}` (the route exists) |
| `/api/v3/services` | 200 | `{"services":[]}` |
| `/api/v3/traces?query.service_name=x&query.start_time_min=<RFC 3339>&query.start_time_max=<RFC 3339>` | 404 | `{"error":{"httpCode":404,"message":"No traces found"}}`: an empty search is a 404, not an empty list |
| `/api/v3/traces/<32 hex>` (unknown ID) | 404 | `{"error":{"httpCode":404,"message":"No traces found"}}` |
| `/api/dependencies?endTs=1` | 200 | `{"data":[],...}` |

Grafana: a provisioned `type: jaeger` datasource at `http://jaeger:16686` gives `GET /api/datasources/uid/<uid>/health` → `400 {"message":"request failed: 404 Not Found","status":"ERROR"}`.

## How to handle it

- Find a known trace with `GET /api/traces/<id>` (`tools/smoke/stack-check.sh`, `check_traces`). Search with `/api/v3/traces?query.service_name=...&query.start_time_min=...&query.start_time_max=...` (`gateway-smoke` skill, Trace scenario).
- With `curl -f`, an empty v3 search or an unknown ID is a 404, so "not yet arrived" and "error" look the same. Poll until a deadline, as `check_traces` does.
- Grafana 13.3.0 cannot search Jaeger 2.22.0, only look up by trace ID. FND-02 smoke (`runs/2026-11-03-smoke-local-stack`, S5): `POST /api/ds/query` with `{"queryType":"search","service":"llmgw-stack-check"}` on the `jaeger` datasource got `400` with `"error":"request failed: 404 Not Found","errorSource":"downstream"`, while a query by trace ID got 200 with the frame. Explore's Jaeger Search tab and service list are unusable. OBS-02 dashboards must not rely on Jaeger search through Grafana; link to traces by ID, or search in the Jaeger UI, which uses `/api/v3`.
- Do not gate on Grafana's Jaeger datasource health. `stack-check.sh` checks only that the datasource exists (FND-02 U6 fallback). Re-check when Grafana or Jaeger is bumped.
