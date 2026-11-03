---
id: bugs/arch-1-misses-jdk-clients-in-core
type: bug
title: ARCH-1 passes a core file that uses a JDK HTTP or SQL client fully qualified, or HttpURLConnection by import
status: active
severity: medium
tags: [konsist, architecture, core, jdk]
related: [bugs/arch-6-misses-qualified-and-typealiased-wiring-annotations, patterns/architecture-rule-with-violating-fixture]
created: 2026-11-03
updated: 2026-11-03
source_files:
  - architecture-tests/src/test/kotlin/io/github/pdroacordi/llmgateway/architecture/ArchitectureRules.kt
  - build-logic/src/main/kotlin/llmgw.kotlin-core.gradle.kts
  - architecture-tests/src/fixtures/kotlin/arch-1-fully-qualified-jdk-client/
  - docs/specs/FND-01-gradle-skeleton.md
---

## Summary

ARCH-1 checks imports only, and spec FND-01 relies on "the Gradle classpath is the first defence" for inline fully qualified names. That premise fails for JDK packages: `java.net.http`, `java.sql` and `javax.sql` are always on the classpath. So `java.net.http.HttpClient.newHttpClient()` written inline in `gateway-core` compiles and passes. `java.net.HttpURLConnection` and `java.net.URL.openConnection()` are not in the forbidden list at all, even as imports.

## Symptom

Found in review of FND-01 (Konsist 0.18.1, scratch fixture in a `gateway-core` layout): a file with `import java.net.HttpURLConnection` and a property typed `reactor.core.publisher.Mono<String>` written inline. `coreImportsArePlainKotlin` reported neither. CLAUDE.md: "No Spring, Reactor, Netty, JDBC/R2DBC or HTTP-client imports in `*-core` ... Konsist tests enforce it."

## Root cause

- `ArchitectureRules.kt:181-190` (`forbiddenImports`) reads `file.imports` only.
- `ArchitectureRules.kt:22-42` (`CORE_FORBIDDEN_IMPORTS`) lists `java.net.http` but not `java.net.HttpURLConnection`, `java.net.URLConnection`, `javax.net.ssl.HttpsURLConnection` or `java.net.Socket`.
- Spec FND-01 section 5 ("Import rules do not see fully qualified names written inline. The Gradle classpath is the first defence") does not hold for JDK packages, nor for non-JDK libraries once a core build file adds them.

## Fix

Fixed in FND-01:

- `CORE_FORBIDDEN_PACKAGES` (`ArchitectureRules.kt:27-48`, renamed from `CORE_FORBIDDEN_IMPORTS`) now forbids all of `java.net` and `javax.net` instead of `java.net.http`. `CORE_ALLOWED_EXCEPTIONS` (`:51-52`) allows the value types `java.net.URI`, `URISyntaxException`, `URLEncoder` and `URLDecoder`.
- `forbiddenReferences` (`:235-252`) checks the imports and also `inlineQualifiedNames` (`:215-224`). That function matches dotted names in the file text after stripping import and package lines, comments and string literals. ARCH-1 to ARCH-5 and ARCH-9 share this helper, so they all see inline names.
- Build scripts, which Konsist does not read: the `checkCoreClasspath` task in `build-logic/src/main/kotlin/llmgw.kotlin-core.gradle.kts:32-91`, wired into `check` at `:93-95`. It fails when a core module's resolved `compileClasspath` or `runtimeClasspath` holds a forbidden group. The list covers Spring, Reactor, Netty, R2DBC, Lettuce, Jedis, Kafka, the OpenTelemetry SDK and exporters, coroutines-reactor/reactive, jOOQ, HikariCP, JDBC drivers, OkHttp, Ktor and Apache HttpClient.
- Spec FND-01 section 5 and the pitfall line of [patterns/architecture-rule-with-violating-fixture](../patterns/architecture-rule-with-violating-fixture.md) no longer claim the classpath covers JDK packages.

The Root cause lines refer to the file before the fix. Commit: the FND-01 commit (`Roadmap: FND-01`).

## Prevention

Added. The fixture `arch-1-fully-qualified-jdk-client` makes `RuleFixturesTest` flag `import java.net.HttpURLConnection` (`JdkClientImport.kt`). It also flags inline `java.net.http.HttpClient`, `java.net.Socket` and `reactor.core.publisher.Mono` (`InlineClients.kt`). Its negative case, `AllowedUri.kt`, holds `java.net.URI` imported and inline, plus forbidden names inside a comment and a string, and must produce no violation. The classpath guard was proven by adding `io.projectreactor:reactor-core` to `gateway-core/build.gradle.kts`: the task and `./gradlew build` failed, and the change was reverted. Limits: comments and strings are stripped by regex, not a parser. A local variable named like a forbidden first segment would be a false positive. `URI.toURL().openConnection()` needs type resolution and is not detected. The general rule: for each forbidden family, ask whether it is on the classpath without a dependency (JDK modules are). Where it is, an import-only rule is not enough.

## Revision (2026-11-03)

Two gaps in the fix, found in the second-pass review. Neither hides a violation in the tree today: no production file has `/*` in a string or a line comment. Low severity.

- **`codeOnly` removes real code** (`ArchitectureRules.kt:298-304`). It runs one regex after another, so block comments are stripped before strings and line comments. A `/*` inside a string or a line comment opens a "comment" that runs to the next `*/`, and everything between is invisible to the rules that read inline names (ARCH-1 to ARCH-5, ARCH-9, ARCH-10, ARCH-11). Run with the exact regexes: `const val GLOB = "/v1/*"`, then `val client = java.net.http.HttpClient.newHttpClient()`, then a KDoc, gives no names; so does `// matches /v1/* paths` in place of the string. Spring path patterns (`"/actuator/**"`) make this likely in app code. The same pass also drops a name inside a template, `"${java.net.http.HttpClient.newHttpClient()}"`, and `'"'` shifts the string matching, so `val q = '"'; val s = "java.net.Socket"` is a false positive.
- **The JDK list stops at `java.net` and `javax.net`** (`:42-43`). `java.nio.channels.SocketChannel`, `com.sun.net.httpserver` and `jdk.net` are on every classpath too, which is the case this entry's general rule names.

Fixed in the second-review fix commit (`Roadmap: FND-01`). `NOT_CODE` (`ArchitectureRules.kt:308`) is one alternation scanned left to right: raw string, block comment, string, char literal, line comment or backtick name, so whichever opens first wins. A name inside a string template stays hidden, which `inlineQualifiedNames`' KDoc states and `HiddenTemplate.kt` pins. `java.nio.channels`, `com.sun.net.httpserver` and `jdk.net` are in `CORE_FORBIDDEN_PACKAGES` (`:44-46`), with a dated line in ADR-FND-01-007. Fixtures, red before the fix: `GlobInString.kt`, `GlobInLineComment.kt`, `QuoteChar.kt`, `QuoteInComments.kt`, `QuoteInBacktickName.kt`, `NioAndJdkServers.kt`; `AllowedLiterals.kt` holds what must not be flagged. A `/` cannot appear in a JVM backtick name, so `"` is the case that matters there.
