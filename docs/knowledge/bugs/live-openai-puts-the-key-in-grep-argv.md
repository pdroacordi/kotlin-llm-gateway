---
id: bugs/live-openai-puts-the-key-in-grep-argv
type: bug
title: live-openai.sh passes the real key to grep as an argument, and a grep that fails reports "key not in the log" as PASS
status: active
severity: medium
tags: [smoke, secrets, live, shell]
related: [runs/2026-11-04-live-openai, bugs/live-openai-reads-a-commented-out-key, gotchas/git-bash-grep-ignore-case-fixed-strings-aborts]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/live-openai.sh
---

## Summary

The script's header says the key "is never printed or passed as an argument", and line 150 passes it as an argument. While that `grep` runs, the paid key is in the process list, where any user on a shared machine can read it. The same line also treats a `grep` error as "not found".

## Symptom

Second-pass review. A `grep` shim on `PATH` recorded each call during a run against a local mock, with a fake key and `LIVE_BASE_URL` set, so nothing reached OpenAI:

```
-qF -- sk-llmgw-review-fake-canary /tmp/tmp.6eD5ZqQYKG/gateway.log
```

The false PASS was shown on its own: `grep -qF -- abc /no/such/file` exits 2, and an `if grep ...; then fail; else pass` takes the `pass` branch.

## Root cause

`tools/smoke/live-openai.sh:150`. `start_gateway "$KEY"` only passes the key between shell functions, and the gateway gets it from the environment, so this is the one place it leaves the shell as an argument. Its `if` has two outcomes for grep's three exit codes.

## Fix

`tools/smoke/live-openai.sh:56`, `check_log_lacks`: requires `[ -s "$LOG" ]`, then compares in the shell with `[[ "$(<"$LOG")" == *"$2"* ]]`, for the key and the canary (`:181`, `:182`). The audit of the rest found no other argument: the key only moves between shell functions and into the gateway's environment. It found more false PASS, all fixed: `usage stripped`, `stream usage is the provider's` and `provider message not relayed` read a `grep` failure, an empty body or a stale one as "absent" (`lacks`, `:49`; `clear_reply`, `:121`), `provider message not relayed` passed on any status, `usage not estimated` passed with no headers, and a process already on the port answered for the gateway (`:101`). Commit: the second-review fix commit.

Proof, a shim on `PATH` recording the arguments of `grep`, `sed`, `tail`, `tr`, `cut`, `curl`, `rm`, `sleep`, `mktemp` and `cat`, and of `java`, with a fake key, `LIVE_BASE_URL` on a local mock and `LIVE_KEY_FILE` on a fake file. Before: one recorded line held the key, and a log removed mid-run printed `PASS key not in the log`. After: no recorded line holds it, and a missing or empty log prints `FAIL key not in the log` and `FAIL prompt not in the log`.

## Prevention

Rule 2 of CLAUDE.md, as a review item for scripts: a secret goes through the environment or a mode-600 file, never `argv`. Keep an exit code above 1 from `grep` a failure, as `git-bash-grep-ignore-case-fixed-strings-aborts` already says. A check that a thing is absent first proves it read something. `tools/smoke/live-openai-test.sh` runs `check_log_lacks` with an empty `PATH`, so it cannot start a process, and `lacks` on an empty file, a missing file and a `grep` error.
