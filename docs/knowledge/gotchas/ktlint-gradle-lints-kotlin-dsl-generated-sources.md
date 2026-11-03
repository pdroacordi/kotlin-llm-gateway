---
id: gotchas/ktlint-gradle-lints-kotlin-dsl-generated-sources
type: gotcha
title: ktlint-gradle lints the hundreds of sources kotlin-dsl generates in an included build-logic
status: active
tags: [ktlint, gradle, kotlin-dsl, configuration-cache, build-logic]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - build-logic/build.gradle.kts
---

## Summary

The `kotlin-dsl` plugin adds its generated accessors, plugin adapters and plugin-spec builders (`build/generated-sources/kotlin-dsl-*`) to the `main` source set. ktlint-gradle 14.2.0 lints the whole source set, so it reports style violations in code nobody wrote. FND-01 counted 446 generated `.kt` files in `build-logic/build/generated-sources/`.

## Details

ktlint-gradle's `filter { exclude(...) }` takes Ant patterns relative to each source *root*. The generated directories are roots themselves, so `exclude("**/generated-sources/**")` or `exclude("**/build/**")` never matches. The exclusion has to compare absolute file paths in a spec lambda. Under the configuration cache that lambda must not capture the script object: `layout` or `project` referenced inside the lambda would be serialized with it.

## How to handle it

`build-logic/build.gradle.kts:17-26`: compute the build directory once, outside the lambda, as a `java.nio.file.Path`, and exclude by `startsWith`:

```kotlin
filter {
    val generated = layout.buildDirectory.get().asFile.toPath()
    exclude { it.file.toPath().startsWith(generated) }
}
```

`Path.startsWith` compares whole path segments, so a sibling directory such as `buildx` is not excluded by accident. On Windows it compares case-insensitively.
