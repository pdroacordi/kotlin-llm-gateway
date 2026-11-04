---
id: bugs/live-openai-reads-a-commented-out-key
type: bug
title: live-openai.sh takes a commented-out line of .env as the value, so an old key or a disabled model is the one used
status: active
severity: low
tags: [smoke, secrets, live, shell]
related: [runs/2026-11-04-live-openai]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - tools/smoke/live-openai.sh
  - .env.example
---

## Summary

`file_value` skips any non-letters before the name, which lets it read `# OPENAI_API_KEY=...`, and it keeps the last match. A commented line below the real one therefore wins, and `.env.example`'s `# LLMGW_LIVE_MODEL=` line takes effect while it is still a comment.

## Symptom

Second-pass review, on a throwaway file, no call made:

```
OPENAI_API_KEY=sk-current-fake
# OPENAI_API_KEY=sk-old-commented-fake
# LLMGW_LIVE_MODEL=commented-model
```

`file_value OPENAI_API_KEY` gives `sk-old-commented-fake`, and `file_value LLMGW_LIVE_MODEL` gives `commented-model`. `export OPENAI_API_KEY=x` gives empty, so the script stops with a message that blames the encoding.

## Root cause

`tools/smoke/live-openai.sh:43`: `^[^A-Za-z]*` was written to pass a byte order mark, and it passes `#` too. `tail -1` then prefers the commented line.

## Fix

`tools/smoke/live-openai.sh:44`: the class before the name is `[^A-Za-z0-9_#]`, so it passes a byte order mark and blanks but neither a comment sign nor part of a longer name such as `_OPENAI_API_KEY`, and an optional `export ` follows it. Commit: the second-review fix commit.

Proof: `tools/smoke/live-openai-test.sh`, 8 of its 17 `.env` shapes red before the fix, all green after it.

## Prevention

`bash tools/smoke/live-openai-test.sh` runs the parsing against a fixture of `.env` shapes (commented lines, `export `, CRLF, quotes, a byte order mark), with no gateway and no key. `LIVE_BASE_URL` already lets the rest of the script run without spending.
