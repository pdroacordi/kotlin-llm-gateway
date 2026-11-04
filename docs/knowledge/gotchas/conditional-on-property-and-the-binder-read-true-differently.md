---
id: gotchas/conditional-on-property-and-the-binder-read-true-differently
type: gotcha
title: ConditionalOnProperty havingValue "true" refuses 1, yes and on, which a Boolean configuration property binds as true
status: active
tags: [spring-boot, configuration]
related: [systems/provider-adapters, bugs/provider-enabled-true-spellings-disagree-with-the-bean-condition]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderConfiguration.kt
---

## Summary

On Spring Boot 4.1.2 and Spring 7, one property read by both `@ConditionalOnProperty(havingValue = "true")` and a `Boolean` in a `@ConfigurationProperties` class can give two answers.

## Details

`OnPropertyCondition.Spec.isMatch` is `requiredValue.equalsIgnoreCase(value)` (read from the bytecode of `spring-boot-autoconfigure-4.1.2`), so only `true`, in any case, matches. `DefaultConversionService.convert(s, Boolean.class)` gave true for `1`, `yes`, `on` and `TRUE`, run against the jar. An environment variable or a command-line argument reaches both as that string.

## How to handle it

Read such a flag from one place. Where a bean condition and a properties class must both see it, assert at startup that they agree.
