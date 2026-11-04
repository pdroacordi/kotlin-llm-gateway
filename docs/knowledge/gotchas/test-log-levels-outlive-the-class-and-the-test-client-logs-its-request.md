---
id: gotchas/test-log-levels-outlive-the-class-and-the-test-client-logs-its-request
type: gotcha
title: A logging.level test property outlives its class in the shared JVM, and WebTestClient logs its own URL and body into the capture
status: active
tags: [testing, logging, spring-boot, webflux]
related: [gotchas/output-capture-all-includes-the-class-level-capture, gotchas/spring-jackson-encoder-logs-the-value-at-debug, bugs/unknown-route-message-echoes-the-path-into-the-debug-log]
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/error/PathNoLeakTest.kt
---

## Summary

Two traps in a test that asserts what a DEBUG log does not hold. `OutputCaptureExtension` captures the test's own HTTP client too, and a level set by property stays set for every class that runs later in the JVM.

## Details

Both came up writing `PathNoLeakTest`, on Boot 4.1.2 and Spring 7.0.10.

- **The client logs too.** With the `web` group at DEBUG, `ExchangeFunctions` logs `HTTP GET http://localhost:<port>/<path>` on the `Test worker` thread. Three of nine first failures were this line, not the gateway: a false red.
- **Levels leak.** `@TestPropertySource(properties = ["logging.level.web=DEBUG"])` changes the logging system when that context starts, and nothing resets it when the class ends. A cached context used later does not start again, so it keeps the level. `ErrorEnvelopeIntegrationTest`'s "malformed JSON" case then failed: `CharSequenceEncoder` logged the client's request body into its capture.

`src/integrationTest`'s `NoLeakIntegrationTest` uses the property form. Its own assertions want the level, and no later class there asserts an absence the client could break.

## How to handle it

- Send the request with `java.net.http.HttpClient`, which logs nothing, so the assertion covers the whole output with no filter.
- Set the levels per test and clear them:

```kotlin
logging.setLogLevel(GATEWAY_LOGGER, level)
checkNotNull(groups.get("web")).configureLogLevel(level, logging::setLogLevel)
```

`LoggingSystem` and `LoggerGroups` are beans; `null` restores the inherited level. This also spares a Spring context, since no property changes the cache key.
- Keep a positive control: assert a line that proves DEBUG was on, or an absence proves nothing.
