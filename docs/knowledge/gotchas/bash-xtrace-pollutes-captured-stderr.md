---
id: gotchas/bash-xtrace-pollutes-captured-stderr
type: gotcha
title: Under bash -x, a function called with 2>&1 inside $(...) captures its own xtrace lines, so scripts that compare captured output report false failures
status: active
tags: [bash, git-bash, debugging, smoke]
related: [systems/local-stack, runs/2026-11-03-smoke-local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - tools/smoke/stack-check.sh
---

## Summary

`bash -x` writes each traced command to stderr. When a script captures a function's output with `out=$(f 2>&1)`, the redirect covers the commands inside `f`, so their trace lines (`++ docker compose ...`) land in `out`. Any comparison on `out` then fails, although a normal run passes.

## Details

Found in the FND-02 smoke (`runs/2026-11-03-smoke-local-stack`, finding 5): `bash -x tools/smoke/stack-check.sh` printed `FAIL services: expected [++ docker compose -f deploy/compose.yaml config --services grafana ...]` and `FAIL redis: redis-cli ping: ++ docker compose ... PONG`. `stack-check.sh` wraps Docker in a `compose()` function and captures `2>&1` to quote errors as evidence.

Minimal reproduction (GNU bash 5.2.37, Git Bash; the same on Linux bash):

```bash
ping_it() { echo PONG; }
out=$(ping_it 2>&1)
[ "$out" = PONG ] && echo "PASS ($out)" || echo "FAIL ($out)"
```

| Run | Output |
|---|---|
| `bash x.sh` | `PASS (PONG)` |
| `bash -x x.sh 2>/dev/null` | `FAIL (++ echo PONG PONG )` |
| `BASH_XTRACEFD=7 bash -x x.sh 7>trace.log` | `PASS (PONG)`, the trace in `trace.log` |

## How to handle it

- Debug such a script with the trace on its own file descriptor: `BASH_XTRACEFD=7 bash -x tools/smoke/stack-check.sh 7>trace.log` (bash manual, `BASH_XTRACEFD`). The note is at the top of `stack-check.sh`.
- Treat a FAIL seen only under plain `-x` as an artefact, not a finding.
