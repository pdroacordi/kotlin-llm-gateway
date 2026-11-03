#!/usr/bin/env bash
# Clean-room scan (CLAUDE.md rule 1): fails when added content or a file path contains a term from
# .clean-room-denylist (local, gitignored; one term per line, case-insensitive, '#' starts a comment).
# Default: staged additions (pre-commit). --all-changes: every changed or untracked file, whole content.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1

LIST=.clean-room-denylist
terms=$(mktemp)
trap 'rm -f "$terms"' EXIT
[ -f "$LIST" ] && grep -vE '^[[:space:]]*(#|$)' "$LIST" | tr -d '\r' | tr '[:upper:]' '[:lower:]' > "$terms"
if [ ! -s "$terms" ]; then
    echo "clean-room: $LIST missing or empty; scan skipped (fill it, CLAUDE.md rule 1)" >&2
    exit 0
fi

found=0
check() { # $1: label; stdin: the text to scan
    local hits rc
    # Lowercase both sides instead of grep -iF: it aborts in GNU grep 3.0 (gotchas/git-bash-grep-ignore-case-fixed-strings-aborts).
    hits=$(tr '[:upper:]' '[:lower:]' | grep -InF -f "$terms")
    rc=$?
    if [ "$rc" -gt 1 ]; then
        echo "clean-room: grep failed (exit $rc) while scanning $1"
        found=1
    elif [ -n "$hits" ]; then
        echo "clean-room: $1"
        printf '%s\n' "$hits" | head -5 | cut -c1-160 | sed 's/^/    /'
        found=1
    fi
}

if [ "${1:-}" = "--all-changes" ]; then
    while IFS= read -r f; do
        [ -f "$f" ] || continue
        [ "$f" = "$LIST" ] && continue
        check "$f" < "$f"
        check "path $f" <<< "$f"
    done < <({ git diff --name-only HEAD 2>/dev/null; git diff --cached --name-only; git ls-files --others --exclude-standard; } | sort -u)
else
    while IFS= read -r f; do
        check "$f" < <(git diff --cached -U0 --no-color -- "$f" | grep -E '^\+' | grep -vE '^\+\+\+ ')
        check "path $f" <<< "$f"
    done < <(git diff --cached --name-only --diff-filter=ACMR)
fi

if [ "$found" -eq 1 ]; then
    echo "clean-room: remove the terms above, or fix the denylist if a term is a false positive"
    exit 1
fi
exit 0
