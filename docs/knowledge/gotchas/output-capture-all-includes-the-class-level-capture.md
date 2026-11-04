---
id: gotchas/output-capture-all-includes-the-class-level-capture
type: gotcha
title: CapturedOutput.all also holds the class-level capture, so a test reads lines logged before it started
status: active
tags: [spring-boot, junit, testing, logging, flaky-test]
related: []
created: 2026-11-04
updated: 2026-11-04
source_files:
  - gateway-app/src/test/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/StalledClientStreamTest.kt
  - gateway-app/src/integrationTest/kotlin/io/github/pdroacordi/llmgateway/gateway/app/chat/StreamDisconnectIntegrationTest.kt
---

## Summary

On Spring Boot 4.1.2, `OutputCaptureExtension` pushes one capture in `beforeAll` and one in `beforeEach`, and `CapturedOutput.all` concatenates both. Every test in a class therefore reads whatever was printed after that class's `beforeAll`, which includes other classes' servers, still running in the context cache.

## Details

Found by CI run 41813666901 (Linux, 2 vCPU): 5 tests of `StalledClientStreamTest` failed on `expected: <1> but was: <2>` and `assertFalse("Stream cancelled" in output.all)`. The extra line came from the previous class's stream, logged at 10:25:07.070, 51 ms before this class's context logged its first line.

From the bytecode of `spring-boot-test` 4.1.2:

- `OutputCapture.getAll` appends each `SystemCapture` in the deque, oldest first: the class capture, then the method capture.
- `PrintStreamCapture` writes through to the real stream, not to its parent capture, so only the newest capture records a line. The class capture is frozen while a test runs and grows only outside tests: before the first `beforeEach`, which covers the context start, and between tests.
- `afterEach` drops the method capture, so one test's lines do not reach the next test's `all`.
- `CapturedOutput.length()` is `toString().length()`, and `toString()` is `getAll()`, so `output.length` is an offset into `all`.
- `supportsParameter` matches the parameter type on any executable, so a `@BeforeEach` method can take `CapturedOutput`, in a Spring test too.

Reproduction: a `BeforeAllCallback` registered after `OutputCaptureExtension` that prints the lines the tests assert on. Applied to all 22 classes that use the extension, it failed 26 tests in 15 classes; with the assertions scoped, 0.

## How to handle it

Any count or absence of a line the gateway logs in normal operation reads only this test's output.

- **The line carries a request ID the test knows:** filter on it, as `ChatStreamRouteTest.kt:272` does. Other streams cannot reach it, before or during the test. A test that gets no response head can take the ID from the mock's journal (`StreamDisconnectIntegrationTest.kt:88`).
- **It does not:** take the length in a `@BeforeEach` and read from there (`StalledClientStreamTest.kt:69` and `:73`). The extension's `beforeEach` runs before `@BeforeEach` methods, so the offset falls after the push.
- **An ID filter on a line without the ID always passes.** Reactor Netty's `Error finishing response` and every line of the mock have none on the line, so their checks use the offset (`StreamFailureIntegrationTest.kt:41`).
- **Scope the wait as well.** An `await` for a line in `all` can pass on a stale copy, and the scoped read that follows then finds nothing.
- **Secrets stay on `all`.** A canary or query string in the log is a bug whichever test printed it, and the class capture adds the context start to the scan (`NoLeakIntegrationTest.kt:30`).

The offset bounds the start only: a stray line that lands after it is still read. The context start usually covers that gap; a class that reuses a cached context has no such gap, so prefer the ID there.
