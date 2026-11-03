---
id: gotchas/konsist-wildcard-import-name-omits-star
type: gotcha
title: Konsist reports a star import's name without the trailing .*
status: active
tags: [konsist, architecture, imports]
related: [patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
---

## Summary

For `import reactor.core.publisher.*`, Konsist 0.18.1 gives `KoImportDeclaration.name == "reactor.core.publisher"` with `isWildcard == true`. The `.*` appears only in `text`. Seen on both Windows and Linux; it is not platform-specific.

## Details

Observed 2026-11-03:

```
IMPORT name=reactor.core.publisher alias=null wildcard=true text=import reactor.core.publisher.*
IMPORT name=org.springframework.stereotype.Component alias=C wildcard=false text=import org.springframework.stereotype.Component as C
```

An alias also leaves `name` as the imported target (`alias` holds `C`).

## How to handle it

Match prefixes on a segment boundary (`name == p || name.startsWith("$p.")`): the package itself then matches its star import. See `String.isInPackage` at `ArchitectureRules.kt:23`. A test that asserts on the substring `reactor.core.publisher` passes whether or not the `.*` is present. Assert `isWildcard` if the distinction matters.
