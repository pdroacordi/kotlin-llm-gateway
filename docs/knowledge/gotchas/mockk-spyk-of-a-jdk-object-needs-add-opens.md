---
id: gotchas/mockk-spyk-of-a-jdk-object-needs-add-opens
type: gotcha
title: MockK's spyk of a JDK object fails with IllegalAccessException, while mockk of a JDK class and spyk of our own classes work on JDK 25
status: active
tags: [mockk, jdk, testing]
related: []
created: 2026-11-03
updated: 2026-11-03
source_files:
  - gradle/libs.versions.toml
---

## Summary

On Temurin 25.0.4.1 with MockK 1.14.11 (Byte Buddy 1.18.2), `spyk(Clock.fixed(...))` throws `IllegalAccessException: class io.mockk.impl.InternalPlatform cannot access a member of class java.time.Clock$FixedClock (in module java.base) with modifiers "private final"`. `mockk<Clock>()`, a `Clock` mock driving `RequestIdGenerator`, `mockk` of a final Kotlin class, and `spyk` of a Kotlin class (open and final members) all pass.

## Details

`spyk(obj)` copies the object's fields into the spy (`InternalPlatform.copyFields`, reached from `AbstractMockFactory.spyk`), and `java.base` does not open `java.time` to unnamed modules. With `--add-opens=java.base/java.time=ALL-UNNAMED` on the test JVM the same test passes.

Warnings on the same runs, none of them failures:

- `A Java agent has been loaded dynamically (...byte-buddy-agent-1.18.2.jar)`, and `Dynamic loading of agents will be disallowed by default in a future release`.
- `sun.misc.Unsafe::objectFieldOffset has been called by net.bytebuddy.dynamic.loading.ClassInjector$UsingUnsafe$Dispatcher$CreationAction`, `will be removed in a future release`.
- `Sharing is only supported for boot loader classes because bootstrap classpath has been appended`.

## How to handle it

Use `mockk<Clock>()` or `Clock.fixed(...)` directly, not a spy of a JDK object. The build sets no `--add-opens`; add one to a module's `test` task only if a test really needs such a spy. The JDK upgrade item rechecks the two "future release" warnings.
