---
id: bugs/smoke-text-format-check-passes-on-an-empty-journal
type: bug
title: anthropic.sh's "4b text no output_config" passes when the journal lookup finds no request
status: active
severity: low
tags: [testing, smoke, anthropic]
related: [bugs/live-openai-puts-the-key-in-grep-argv, runs/2026-11-05-smoke-tools-and-response-format]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - tools/smoke/anthropic.sh
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/AdminHandlers.kt
---

## Summary

`check_lacks` refuses only an empty file, and a journal lookup that matches nothing is not empty, so the check of `{"type":"text"}` proves nothing when the entry is missing.

## Symptom

Found in the GW-06 review, by reading. If `journal "$(header x-request-id)"` matches no entry (a missing header, a changed filter), `$TMP/entry` holds `{"data":[]}`, `output_config` is not in it, and `4b text no output_config` prints PASS on both forms.

## Root cause

`tools/smoke/anthropic.sh:202-203` runs `check_lacks` with no positive check on the same entry. `AdminHandlers.kt:62-67` always writes the `data` array, so the `[ ! -s "$file" ]` guard in `check_lacks` (`anthropic.sh:47`) never fires for a lookup that found nothing. The `json_schema` branch at `anthropic.sh:197-199` is safe, because a `check_has` reads the entry first.

## Fix

Fixed in GW-06 (Roadmap: GW-06). `tools/smoke/anthropic.sh:206` runs `check_has "4b text reached the mock"` on `'"route":"anthropic.messages"'` before the `check_lacks` at `:207`, so a lookup that found nothing fails. On a scratch copy with the lookup replaced by `{"data":[]}`, the script as found printed PASS on both forms and the fixed one prints `FAIL 4b text reached the mock` ([run](../runs/2026-11-05-smoke-tools-and-response-format.md), finding 1).

## Prevention

A `check_lacks` on a journal entry follows a `check_has` on that entry, as `anthropic.sh:206-207` does.
