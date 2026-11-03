---
id: gotchas/blockhound-versions-carry-release-suffix
type: gotcha
title: BlockHound is published as 1.0.18.RELEASE; the version 1.0.18 does not resolve
status: active
tags: [blockhound, gradle, version-catalog]
related: [systems/mock-provider]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gradle/libs.versions.toml
  - build-logic/src/main/kotlin/llmgw.spring-app.gradle.kts
---

## Summary

`io.projectreactor.tools:blockhound` and `blockhound-junit-platform` publish every version with a `.RELEASE` suffix (Maven Central metadata lists `1.0.13.RELEASE` to `1.0.18.RELEASE`). A catalog entry `blockhound = "1.0.18"` fails only when a module first resolves it.

## Details

FND-01 pinned `1.0.18`. No module used it until FND-03, so the wrong coordinate went unnoticed. Checked against `https://repo1.maven.org/maven2/io/projectreactor/tools/blockhound/maven-metadata.xml` and the Gradle cache (`files-2.1/io.projectreactor.tools/blockhound/1.0.18.RELEASE`).

## How to handle it

Use the full version string: `gradle/libs.versions.toml`, `blockhound = "1.0.18.RELEASE"`. When adding a catalog version for a library nothing uses yet, resolve it once (for example `./gradlew :<module>:dependencies`) before committing.
