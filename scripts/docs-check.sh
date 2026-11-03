#!/usr/bin/env bash
# Living-docs consistency; prints every problem and exits 1 if there is any.
# Knowledge entries carry the id of their path and are indexed; INDEX links resolve; roadmap IDs are
# unique and every row has a valid status; specs map to roadmap rows; CLAUDE.md systems links resolve.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

fail=0
err() { echo "docs-check: $1"; fail=1; }
fm_id() { sed -n 's/^id:[[:space:]]*//p' "$1" | head -1 | tr -d '\r' | sed 's/[[:space:]]*$//'; }

KB=docs/knowledge
for dir in systems patterns bugs gotchas runs; do
    for f in "$KB/$dir"/*.md; do
        [ -e "$f" ] || continue
        slug=$(basename "$f" .md)
        [ "$slug" = "_template" ] && continue
        id="$dir/$slug"
        [ "$(fm_id "$f")" = "$id" ] || err "$f: frontmatter id should be '$id'"
        grep -qF "($id.md)" "$KB/INDEX.md" || err "$f: not listed in $KB/INDEX.md"
    done
done

missing=$(grep -oE '\]\([a-z]+/[A-Za-z0-9._-]+\.md\)' "$KB/INDEX.md" | sed -E 's/^\]\((.*)\)$/\1/' |
    while read -r rel; do [ -f "$KB/$rel" ] || printf '%s ' "$rel"; done)
[ -n "$missing" ] && err "$KB/INDEX.md links to missing entries: $missing"

ROADMAP=docs/roadmap.md
rows=$(grep -E '^\| [A-Z]+-[0-9]+ ' "$ROADMAP")
ids=$(printf '%s\n' "$rows" | cut -d'|' -f2 | tr -d ' ')
dups=$(printf '%s\n' "$ids" | sort | uniq -d | tr '\n' ' ')
[ -n "$dups" ] && err "duplicate roadmap IDs: $dups"
bad=$(printf '%s\n' "$rows" | grep -vE '`\[[ ~x?!-]\]`' | cut -d'|' -f2 | tr -d ' ' | tr '\n' ' ')
[ -n "$bad" ] && err "roadmap rows without a valid status: $bad"

for f in docs/specs/*.md; do
    [ -e "$f" ] || continue
    name=$(basename "$f" .md)
    [ "$name" = "_template" ] && continue
    sid=$(printf '%s' "$name" | grep -oE '^[A-Z]+-[0-9]+')
    if [ -z "$sid" ]; then
        err "$f: the name must start with a roadmap ID (RL-01-<slug>.md)"
        continue
    fi
    printf '%s\n' "$ids" | grep -qx "$sid" || err "$f: $sid is not a roadmap row"
    [ "$(fm_id "$f")" = "$sid" ] || err "$f: frontmatter id should be '$sid'"
done

for rel in $(grep -oE 'docs/knowledge/systems/[a-z0-9-]+\.md' CLAUDE.md | sort -u); do
    [ -f "$rel" ] || err "CLAUDE.md links missing $rel"
done

exit $fail
