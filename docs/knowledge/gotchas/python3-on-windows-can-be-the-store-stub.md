---
id: gotchas/python3-on-windows-can-be-the-store-stub
type: gotcha
title: python3 on Windows can be the Store stub, which exits 49 and prints nothing
status: active
tags: [windows, python, smoke]
related: [runs/2026-11-05-smoke-embeddings]
created: 2026-11-05
updated: 2026-11-05
source_files: []
---

## Summary

On this machine `python3` is the Windows Store stub: it exits 49 with no output, so a smoke check that compares two Python results passes on two empty strings.

## Details

`embeddings.sh` as first written reported `base64 byte-identical to the mock` as a pass on `'' = ''`. `python` was the real interpreter.

## How to handle it

Take the first interpreter that runs `pass`, stop if there is none, and never compare computed values without refusing empty ones: `check_same` in `tools/smoke/`.
