---
id: bugs/mock-scenario-error-headers-not-validated
type: bug
title: A scenario error header with an invalid name is accepted with 204, then every request to that model fails with a 500 logged as a mock bug
status: active
severity: low
tags: [mock-provider, admin-api, validation]
related: [systems/mock-provider, bugs/mock-scenario-times-expanded-into-memory]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt
  - mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/web/Serving.kt
  - mock-provider/src/test/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJsonTest.kt
---

## Summary

`error.headers` names and values are stored unchecked. Netty validates them only when the injected error is written, so a typo in a test turns into a 500 and an ERROR log with a stack trace on every request to that model, instead of a 400 at `PUT` time naming the member.

## Symptom

Found in the FND-03 review (2026-11-03) against the boot jar: `PUT /__mock/scenarios/h` with `{"otherwise":{"error":{"status":429,"headers":{"bad name":"x"}}}}` returned 204. Every chat request to `h` then got 500 `server_error`, logged as `IllegalArgumentException` from `DefaultHttpHeadersFactory$1.validateName`.

## Root cause

`ScenarioJson.error` checked only that each header value is a JSON string (`ScenarioJson.kt:96-103` before the fix). `Serving.error` sets the headers on the response (`Serving.kt:204`), and Netty's header validation throws there.

## Fix

Fixed in FND-03. `ScenarioJson.error` (`mock-provider/src/main/kotlin/io/github/pdroacordi/llmgateway/mockprovider/admin/ScenarioJson.kt:98-110`) rejects a name that is not an RFC 9110 token (`:104`; `TOKEN` at `:206`) and a value that is not a string or holds CR, LF or NUL (`:105-107`; `FORBIDDEN_IN_VALUE` at `:207`), with a 400 whose `param` is `<path>.headers.<name>`. The scenario is rejected before it is stored, so the stored scenarios are unchanged. Commit: the FND-03 commit (`Roadmap: FND-03`).

## Prevention

Added for FND-03: `ScenarioJsonTest` "every rejection is a 400 naming the member" rows `:126` (the name `bad name`), `:127-128` (a value with `\r\n` that would inject a second header), `:129` (a NUL in the value) and `:130` (an empty name). `AdminApiIntegrationTest` "invalid scenarios are 400 naming the member and leave the stored scenarios unchanged" covers the store side of a rejection.

## Revision (2026-11-03)

The fix covers names but only part of the values. `FORBIDDEN_IN_VALUE` (`ScenarioJson.kt:207`, used at `:105`) rejects CR, LF and NUL, while Netty 4.2.19 rejects more on a response header, so such a scenario is still stored with 204 and every request that takes the step fails.

Found in the second-pass review, on a real Reactor Netty 1.3.8 server with `response.headers.set("x-scenario", value)`:

| Value | Netty | Client |
|---|---|---|
| `a<0x01>b` | `prohibited character 0x1 at index 1` | 500 |
| `a<0x7F>b` | `prohibited character 0x7f at index 1` | 500 |
| `a<0x0B>b` | `prohibited character 0xb at index 1` | 500 |
| leading space | `prohibited character 0x20 at index 0` | 500 |
| leading tab | `prohibited character 0x9 at index 0` | 500 |

The failure is `IllegalArgumentException: Validation failed for header 'x-scenario'`, raised with the response uncommitted, so a mapper shaped like the mock's writes its 500 (client: `500` with the mapper's body; the mock's mapper also logs ERROR with a stack trace for a 5xx). `Serving.error` has already recorded `error_injected` with the scenario's status (`Serving.kt:200`), so the journal also disagrees with what the client got.

Fixed in the second-review fix commit (`Roadmap: FND-03`). `ScenarioJson.isHeaderValue` (`ScenarioJson.kt:212`, used at `:105`) mirrors Netty's rule: a non-empty value's first character is above 0x20 and not 0x7F; later characters are not below 0x20 (tab excepted) and not 0x7F. `ScenarioJsonTest` "every rejection is a 400 naming the member" has a row for each value above, red before the fix, and `ScenarioJsonTest:111` accepts an embedded tab. The systems entry's limit line says the same.
