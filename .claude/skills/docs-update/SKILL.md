---
name: docs-update
description: Update agent-gateway's living docs in the canonical order (spec, roadmap, DECISIONS, GLOSSARY, systems entry and CLAUDE.md row, knowledge entries, diagrams, knowledge INDEX) and run docs-check, when an item starts, advances or is done. Use at step 8 of feature-loop, after any change to a system's behaviour, and when the user says "atualiza a doc", "update the docs", "fecha o item".
---

# docs-update

Each file answers one question (CLAUDE.md, first table). Writing them in a fixed order is what keeps links from going stale and indexes from drifting: the spec is the source of truth for the item, the roadmap is the catalogue, `DECISIONS.md` holds cross-cutting decisions, the systems entry is the current contract.

## Order

1. **`docs/specs/<ID>-<slug>.md`** (if the item has one): frontmatter `status` (`not-started` → `in-progress` → `done`) and `updated`; acceptance criteria ticked only when verified; local ADRs (`ADR-<ID>-00N`) in its ADR section; a log entry (date, who, what was done, decisions, next step). The log is append-only: a mistake is corrected by a new entry.
2. **`docs/roadmap.md`**: the row's status, `Updated` date, and Notes (spec link, systems entry link, one-line outcome). Follow-ups become new rows with the next free ID in their area.
3. **`docs/DECISIONS.md`**: only for a decision that affects several items or the whole system. `ADR-NNN` with Status, Date, Context, Options, Decision, Consequences, appended before the template comment at the end. Reference it from the spec. A `Proposed` ADR that the item settled becomes `Accepted` (or `Superseded by`), with a dated line saying why.
4. **`docs/GLOSSARY.md`**: new domain terms the code or API introduced, alphabetical, with the code identifier.
5. **`docs/knowledge/systems/<system>.md`**: rewritten to stay current when the system's behaviour changed; created from `_template.md` for a new system, and its link put in the CLAUDE.md systems table (replacing the `(ID)` placeholder) with the one rule most likely to be broken.
6. **`docs/knowledge/` patterns, gotchas, bugs, runs**: new entries for what was learned. One concept per file.
7. **`docs/diagrams/`**: the `diagram-update` skill, when a flow, a sequence, the module graph or the data model changed.
8. **`docs/knowledge/INDEX.md`**: one line per new or changed entry, in the same edit.
9. **Check:** `bash scripts/docs-check.sh` must exit 0.

## Pitfalls

- **Grep before editing.** Find the exact row or section first; editing a long file from memory duplicates sections.
- **Never mark `[x]` with an acceptance criterion still open.** Code done but smoke not run is `[~]` with the open item named in Notes.
- **A skipped item is `[-]`** with the reason in Notes; IDs are never reused.
- **A user decision is quoted**, in their words, with the date, where it changes something (spec ADR, systems entry or roadmap Notes).
- **No employer-internal content** in any doc (CLAUDE.md rule 1), including "how we did it at work".
