---
id: gotchas/reactor-client-connector-needs-spring-context-under-warnings-as-errors
type: gotcha
title: ReactorClientHttpConnector implements SmartLifecycle, so Kotlin with warnings as errors needs spring-context on the compile classpath
status: active
tags: [spring, webclient, kotlin, build]
related: [systems/provider-adapters]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-providers/build.gradle.kts
---

## Summary

`spring-web` 7.0.10's `ReactorClientHttpConnector` implements `org.springframework.context.SmartLifecycle`, but neither `spring-web` nor `spring-webflux` publishes a dependency on `spring-context`. A module that uses WebClient without Boot's starter compiles with a warning, which `allWarningsAsErrors` turns into a failure.

## Details

Kotlin 2.4.21, one line that calls `ReactorClientHttpConnector(HttpClient.create())`:

- Without `spring-context`: `warning: cannot access 'org.springframework.context.SmartLifecycle' which is a supertype of ... This may be forbidden soon.` With `-Werror`, exit 1.
- With it: exit 0.

The published POMs list only `spring-beans` and `spring-core` (and `spring-web` for `spring-webflux`), so the dependency is not visible as `optional` in them.

## How to handle it

Add `implementation(libs.spring.context)` to the module (`gateway-providers/build.gradle.kts`). ARCH-6 still keeps wiring annotations out of it.
