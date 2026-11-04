---
id: bugs/provider-enabled-true-spellings-disagree-with-the-bean-condition
type: bug
title: LLMGW_PROVIDERS_ANTHROPIC_ENABLED=1 starts with no adapter and no key check, and every claude- model is a 404
status: active
severity: low
tags: [configuration, spring-boot, anthropic, startup]
related: [systems/provider-adapters, gotchas/conditional-on-property-and-the-binder-read-true-differently]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/config/ProviderConfiguration.kt
---

## Summary

The adapter bean and the resolver decide "enabled" by two readers that disagree on `1`, `yes` and `on`, so a provider can be half enabled: prefixes route to it, and it has no adapter.

## Symptom

Found in the GW-03 review. Each half was checked against the Boot 4.1.2 and Spring 7 jars; the whole was not run. With `LLMGW_PROVIDERS_ANTHROPIC_ENABLED=1` the app starts with a blank `ANTHROPIC_API_KEY`, and `claude-x` gives 404 `model_not_found`. ADR-GW-03-004 chose an explicit flag so that misconfiguration stops at deploy.

## Root cause

`@ConditionalOnProperty(havingValue = "true")` (`ProviderConfiguration.kt:33`, `:42`) compares strings, so `1` does not match and no adapter exists. `AnthropicProperties.enabled` is bound through the conversion service, so it is true, and `modelResolver` (`ProviderConfiguration.kt:70`) loads the prefixes. `selection.validate` sees only the default provider, which is enabled. With OpenAI the same value stops the start, but only because OpenAI is the default.

## Fix

Fixed in GW-03 (Roadmap: GW-03). `chatModelPort` calls `checkEnabled` for each provider (`ProviderConfiguration.kt:62`, `:83`), which stops the start with `llmgw.providers.<provider>.enabled must be written true or false: its value and the adapter disagree.`, naming the property only.

## Prevention

`ProviderStartupTest`: `a spelling of true that binds but makes no Anthropic adapter stops the start` and its OpenAI twin, on `1`, `yes` and `on`. The smoke saw exit 1 on those spellings ([run](../runs/2026-11-04-smoke-anthropic-adapter.md), startup table). The general rule is in the [gotcha](../gotchas/conditional-on-property-and-the-binder-read-true-differently.md).
