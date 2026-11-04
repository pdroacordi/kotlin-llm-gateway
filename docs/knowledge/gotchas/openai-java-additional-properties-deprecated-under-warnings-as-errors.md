---
id: gotchas/openai-java-additional-properties-deprecated-under-warnings-as-errors
type: gotcha
title: openai-java 4.98.0 deprecates _additionalProperties(), so calling it fails a Kotlin build with allWarningsAsErrors
status: active
tags: [openai, sdk, kotlin, build]
related: [systems/provider-adapters]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/OpenAiSdkStreamContractTest.kt
  - build-logic/src/main/kotlin/llmgw.kotlin-jvm.gradle.kts
---

## Summary

`ChatCompletionChunk._additionalProperties()` in `openai-java-core` 4.98.0 carries `@kotlin.Deprecated`, message "Use _allProperties() to access both declared and additional JSON properties." Most SDK examples still show it. This project sets `allWarningsAsErrors = true`, so the deprecation warning is a compile failure.

## Details

`javap -v` on `com.openai.models.chat.completions.ChatCompletionChunk` shows `Deprecated: true` on the method and the annotation above. `_allProperties()` returns a map with the declared members too, so test a name's presence, not the map's size.

## How to handle it

Read an unknown member with `_allProperties()[name]`, as `OpenAiSdkStreamContractTest` does for `x_gateway`. `ChatChunkWriter.EXTENSION_MEMBER` keeps the name in one place.
