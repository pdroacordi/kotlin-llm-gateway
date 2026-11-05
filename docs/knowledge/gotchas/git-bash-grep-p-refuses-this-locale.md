---
id: gotchas/git-bash-grep-p-refuses-this-locale
type: gotcha
title: Git Bash's grep -P refuses to run in this locale
status: active
tags: [git-bash, grep, smoke, windows]
related: [gotchas/git-bash-grep-ignore-case-fixed-strings-aborts, runs/2026-11-05-smoke-embeddings]
created: 2026-11-05
updated: 2026-11-05
source_files: []
---

## Summary

In Git Bash here, `grep -P` stops with `supports only unibyte and UTF-8 locales`, so a smoke script using it checks nothing.

## Details

Seen writing the GW-05 smoke run. The failure is an exit code, which a `check_lacks` or an `if grep` reads as no match.

## How to handle it

Use `grep -E` in `tools/smoke/`. To match a backslash and the character after it write two backslashes and a dot: one backslash and a dot is a literal dot.
