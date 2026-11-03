---
name: preflight
description: Session preflight for kotlin-llm-gateway, at the start of every session and before a feature, a feature-loop run, a migration or a commit - branch vs upstream (with a warning when CLAUDE.md, skills, hooks or DECISIONS.md changed upstream), git hooks path, JDK, Docker and the local stack, the clean-room denylist, docs-check, items in progress, and the parallel-work questions. Use when the SessionStart hook prints "PREFLIGHT found problems", and when the user says "bora começar", "pega o próximo item", "vamos codar", "antes de commitar" or "confere o ambiente".
---

# preflight

The SessionStart hook (`.claude/settings.json`) runs `bash scripts/preflight.sh --quiet`, which prints only problems. Run the whole skill before a feature, a loop or a commit even when the hook was silent.

## Steps

1. **Mechanical part:**
   ```bash
   bash scripts/preflight.sh
   ```
   It fetches with an 8 s timeout, shows ahead/behind against the upstream and the rule files changed upstream, checks `core.hooksPath`, the JDK (21+), Docker and the compose services, the clean-room denylist, `docs-check`, and lists roadmap items in progress. It never blocks (exit 0).
2. **Behind the upstream: stop.** Show the missing commits and the rule files they change. With the user's ok, `git pull --rebase` (a dirty tree: ask first). After the pull, re-read the changed rule files before going on.
3. **Hooks:** `core.hooksPath` must be `.githooks`. Otherwise: `git config core.hooksPath .githooks`.
4. **Clean-room denylist:** `.clean-room-denylist` missing or empty means the pre-commit scan cannot catch employer terms (CLAUDE.md rule 1). Ask the user to fill it; never fill it yourself from guesses, and never print its contents into a commit, a doc or a prompt to another tool.
5. **Ask the user**, before a feature or a loop:
   - "Work in a `git worktree` (isolated) or in the main checkout?"
   - "Is another agent working at the same time right now?"

   With another agent on the same branch or stack, run no migration, no build and no smoke run without agreeing first. A worktree that needs its own stack uses a compose project name and port overrides (`COMPOSE_PROJECT_NAME` and `LLMGW_*_PORT` in `deploy/.env`, from `deploy/.env.example`; systems entry `local-stack`).
6. **Items in progress:** a `[~]` row is continued before any new item is started.
7. **Local stack** (after FND-02): `docker compose -f deploy/compose.yaml up -d --wait` if the item needs it. Ports are in CLAUDE.md. A "port ... is already in use" problem from the script means another program holds a stack port: on Docker Desktop `up` would still succeed and the port would reach that program, so set the variable in `deploy/.env` first.
8. **Summarize in at most 5 lines** (branch and upstream, hooks, denylist, answers to step 5, items in progress) and only then go to the roadmap.

## Pitfalls

- `git fetch` offline or hung: the script says so. The comparison is stale; say that instead of "up to date".
- A branch with no upstream is normal for a local-only repo.
- A dirty tree at the start may be another agent's work. No stash, reset or checkout on it without asking.

## When to stop and ask

Behind the upstream, a dirty tree that is not yours, another agent active and the item touches schema or the shared stack, or the denylist missing before a commit.
