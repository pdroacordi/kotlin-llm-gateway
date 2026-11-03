---
id: gotchas/git-bash-grep-ignore-case-fixed-strings-aborts
type: gotcha
title: GNU grep 3.0 in Git Bash aborts on -i combined with -F, and a script can read that as "no match"
status: active
tags: [tooling, windows, git-bash, grep, scripts]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files: []
---

## Summary

On Windows, Git Bash ships GNU grep 3.0, which aborts (exit 134, `Aborted`) whenever `-i` is combined with `-F`, with `-e` or `-f`. Inside `$(...)` the abort prints nothing on stdout, so a script that tests for empty output treats the crash as "no match".

## Details

Found while testing the clean-room scan (2026-11-03): a staged file containing a denylisted term passed the scan with exit 0.

```bash
echo "x AcmeInternal y" | grep -i -F -e acmeinternal   # Aborted, exit 134
echo "x acmeinternal y" | grep -inF -f terms.txt       # Aborted, exit 134
echo "x acmeinternal y" | grep -nF -f terms.txt        # works
```

`grep -iE` (regex, not fixed strings) is not affected.

## How to handle it

- Do not combine `-i` and `-F` in scripts that may run in Git Bash. Lowercase both the patterns and the input with `tr '[:upper:]' '[:lower:]'` and use `grep -F`.
- In any check whose silence means "pass", capture grep's exit code and fail when it is greater than 1 (2 means error, 134 means abort).

## Revision (2026-11-03)

`scripts/clean-room-scan.sh`, where this was found, was removed with the denylist (ADR-001). The behaviour still applies to any script run in Git Bash.
