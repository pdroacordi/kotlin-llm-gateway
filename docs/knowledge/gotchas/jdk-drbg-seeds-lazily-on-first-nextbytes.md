---
id: gotchas/jdk-drbg-seeds-lazily-on-first-nextbytes
type: gotcha
title: SecureRandom.getInstance("DRBG") does not seed; the first nextBytes reads entropy, and every nextBytes takes the instance's lock
status: active
tags: [jdk, securerandom, request-id, blockhound]
related: [systems/request-context-and-errors]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/request/RequestContextConfiguration.kt
---

## Summary

On JDK 25.0.4 (Temurin), `SecureRandom.getInstance("DRBG")` only configures a `Hash_DRBG,SHA-256,128,reseed_only`. Instantiation, including the entropy read and the static seeder's first `SeedGenerator` read (`securerandom.source=file:/dev/random`), happens inside the first `nextBytes`.

## Details

- With `-Djava.security.debug=securerandom`, `getEntropy(...)` and `instantiate` appear only after the first `nextBytes`, never after `getInstance`. 100 000 later 10-byte draws log no entropy read.
- `AbstractDrbg.engineNextBytes` (JDK `src.zip`, `sun/security/provider/AbstractDrbg.java`) calls the `synchronized` `instantiateIfNecessary` on every draw. `HashDrbg.generateAlgorithm` is also `synchronized`, so one shared instance serializes every caller.
- `reseedInterval` is `Integer.MAX_VALUE`, and `HashDrbg` increments an `int` counter per draw. After about 2^31 draws the counter overflows and the instance reseeds. A non-prediction-resistant reseed reads from the process-wide seeder DRBG in memory, not from a file, so it does not block.
- `UUID.randomUUID()` uses `NativePRNG`, which does read a file per call (spring-framework#24027).
- Under BlockHound (2026-11-03, a probe on a non-blocking thread; Linux is `eclipse-temurin:25-jdk`, 25.0.5; Windows is Temurin 25.0.4):

| Call on a non-blocking thread | Linux | Windows |
|---|---|---|
| First draw of an unseeded DRBG | `FileInputStream#readBytes` | passes |
| `UUID.randomUUID()` | `FileInputStream#readBytes` | passes |
| 100 000 10-byte draws after one draw at startup | passes | passes |
| `reseed()` after one draw at startup | passes | passes |
| First draw of a second DRBG, seeder already up | passes | passes |

Windows seeds natively, so BlockHound sees no read there: removing the startup draw leaves `BlockHoundIntegrationTest` green on Windows. Only a Linux run (CI) guards that draw: removing it in a Linux container failed 3 of the 4 tests, with `FileInputStream#readBytes`.

## How to handle it

- Draw once where blocking is allowed, at bean creation (`RequestContextConfiguration.kt:17-20`), so that request threads never instantiate the DRBG.
- If OPS-01 measures lock contention on the shared instance, use one DRBG per event-loop thread, each seeded at startup.
