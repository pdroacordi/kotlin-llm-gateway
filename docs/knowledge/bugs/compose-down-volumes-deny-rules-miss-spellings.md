---
id: bugs/compose-down-volumes-deny-rules-miss-spellings
type: bug
title: The agent deny rules let docker compose down with flags before -v, and plain docker-compose down -v, through
status: active
severity: low
tags: [claude-settings, docker, compose, local-stack]
related: [systems/local-stack]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - .claude/settings.json
  - docs/knowledge/systems/local-stack.md
---

## Summary

`.claude/settings.json` denies `down -v` "in any spelling" according to the systems entry (contract rule 4), but a pattern with ` * down` needs an argument between `compose` and `down`. So the spellings with nothing there pass unless `-v` comes straight after `down`.

## Symptom

Found in the second-pass review by matching the deny list as documented: `*` is any characters, spaces around it are literal. This models the rules; Claude Code itself was not run against them.

| Command | Result |
|---|---|
| `docker compose -f deploy/compose.yaml down -t 5 -v` | denied |
| `docker compose down -v` | denied |
| `docker compose down --remove-orphans -v` | allowed |
| `docker compose down -t 5 --volumes` | allowed |
| `docker-compose down -v` | allowed |
| `docker-compose down --volumes` | allowed |
| `docker-compose -f deploy/compose.yaml down -t 5 -v` | allowed |
| `docker volume rm -f llmgw_postgres-data` | allowed |

The `docker compose down ...` cases need the compose file to resolve, which they do from `deploy/` or with `COMPOSE_FILE` set.

## Root cause

`.claude/settings.json:23-26` have ` * down`, whose literal spaces need text between `compose` and `down`; `:21-22` cover only `-v` straight after `down`. `:27-28` have the same shape for `docker-compose`, with no bare form and no flags-between form. `:29` has no form with a flag before the volume name.

## Fix

Fixed in `.claude/settings.json` (deny list, the `docker compose down * -v*` family, the `docker-compose` equivalents and `docker volume rm * llmgw_*`), in the second-review fix commit.

Nothing legitimate is over-matched: `down` without `-v`, with `--remove-orphans`, with a service name, and with `-p <name>` all stay allowed, and a Compose service name cannot start with `-`. One case to watch: if the rules see the whole pipeline, `docker compose -f deploy/compose.yaml down 2>&1 | grep -v warning` is denied by `:25`. That fails safe; drop the `grep`.

## Prevention

A deny list is a guard, not proof, so the rule stays in the skills and agents too. When widening one, list each spelling and check it against the patterns, including the form with no argument where a pattern has ` * `.
