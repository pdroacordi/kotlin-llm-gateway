---
id: gotchas/gradle-version-catalog-alias-prefix-needs-asprovider
type: gotcha
title: A catalog alias that is a prefix of another alias becomes a group; read it with asProvider()
status: active
tags: [gradle, version-catalog, build-logic]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gradle/libs.versions.toml
  - build-logic/build.gradle.kts
---

## Summary

`gradle/libs.versions.toml` declares versions `ktlint` (the engine, 1.8.0) and `ktlint-gradle` (the plugin, 14.2.0). Gradle turns `-` into nesting in the generated accessors, so `libs.versions.ktlint` is a *group* with a child `gradle`, not the `ktlint` version. Gradle 9.7.1 behaves this way, and libraries and plugins work the same.

## Details

`ktlint { version = libs.versions.ktlint }` does not compile: the accessor type is a group, not a `Provider<String>`. The group's own value is reached with `asProvider()` (Gradle user guide, "Version catalogs"). Precompiled script plugins, which use `VersionCatalogsExtension.findVersion("ktlint")` by string, are not affected.

## How to handle it

- In a build script: `libs.versions.ktlint.asProvider()` (`build-logic/build.gradle.kts:16`).
- In a precompiled convention plugin: `libs.findVersion("ktlint").get().requiredVersion` (`llmgw.kotlin-jvm.gradle.kts:44`).
- When naming a new alias, avoid making it a prefix of another (`ktlint-engine` and `ktlint-gradle`), unless renaming would break existing references.
