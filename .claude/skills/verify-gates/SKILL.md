---
name: verify-gates
description: Run kotlin-llm-gateway's quality gates in order - tests, coverage, static analysis, rule scans, docs check, clean-room and git hygiene, full build - stopping at the first failure. Use before marking a roadmap item done, before every commit of code, and when the user says "verify", "run the gates", "is it green", "roda os portões".
---

# verify-gates

Run in order and stop at the first red gate. Report each gate as PASS/FAIL with the evidence (counts, the offending lines). Write logs to the session scratchpad (`LOGS=<scratchpad>/gates`), never the repo.

Until FND-01 lands there is no Gradle build: only gates 5 and 6 apply. Say so instead of reporting green.

**Check the real exit code.** Capture `RESULT=$?` right after the command. A pipe (`| tail`), a trailing `echo` or a wrapper hides a red gate. Then read the summary itself, not only the code.

## 1. Tests

```bash
./gradlew test --console=plain > "$LOGS/gate_test.log" 2>&1; RESULT=$?; echo "exit $RESULT"
grep -E "BUILD (SUCCESSFUL|FAILED)|tests completed|FAILED" "$LOGS/gate_test.log" | tail -20
```

PASS: exit 0 and `BUILD SUCCESSFUL`. On failure quote the failing class, test and assertion. The per-test XML is in `*/build/test-results/test/`. The Konsist architecture rules run here too.

## 2. Coverage

```bash
./gradlew koverVerify --console=plain > "$LOGS/gate_cov.log" 2>&1; echo "exit $?"
```

Thresholds live in the build (80% lines in `*-core`, 70% overall). Report the measured numbers for the modules the change touched (`./gradlew koverLog`).

## 3. Static analysis

```bash
./gradlew ktlintCheck detekt --console=plain > "$LOGS/gate_static.log" 2>&1; echo "exit $?"
```

Never fix a finding by adding it to a baseline or a suppression without a one-line reason in the code.

## 4. Rule scans

Each must print nothing. A hit is a FAIL unless the line is a documented exception; then name it.

```bash
# Core is plain Kotlin (CLAUDE.md: Architecture).
grep -rnE '^import (org\.springframework|reactor\.|io\.netty|io\.r2dbc|java\.sql|org\.jooq|io\.lettuce|org\.apache\.kafka|io\.opentelemetry\.(sdk|exporter))' --include=*.kt */src/main | grep -E '^[a-z-]*-core/'
# Core takes time from an injected clock.
grep -rnE 'Instant\.now\(\)|System\.currentTimeMillis|LocalDateTime\.now\(\)' --include=*.kt */src/main | grep -E '^[a-z-]*-core/'
# Money is never floating point.
grep -rnE '\b(Double|Float)\b' --include=*.kt */src/main | grep -iE 'cost|price|budget|usd|money'
# No blocking bridges or global scopes in production code.
grep -rnE '\brunBlocking\b|\bGlobalScope\b|\.block\(\)|\.blockFirst\(\)|\.blockLast\(\)' --include=*.kt */src/main
# No non-null assertions.
grep -rnE '!!' --include=*.kt */src/main
# Prompt or completion text in logs (heuristic: read each hit).
grep -rnE 'log(ger)?\.(trace|debug|info|warn|error)\(.*\b(prompt|messages|content|completion)\b' --include=*.kt */src/main
# Tests never reach a real provider outside a live-tagged test.
grep -rlE 'api\.openai\.com|api\.anthropic\.com|generativelanguage\.googleapis\.com' --include=*.kt */src/test | xargs -r grep -L 'Tag("live")'
# No commented-out code.
grep -rnE '^\s*//\s*(val|var|fun|if|for|while|return|import|class)\b' --include=*.kt .
```

## 5. Docs

```bash
bash scripts/docs-check.sh; echo "exit $?"
```

Knowledge entries indexed with matching IDs, specs on the roadmap, roadmap IDs unique, CLAUDE.md systems links resolve.

## 6. Clean-room and git hygiene

```bash
bash scripts/clean-room-scan.sh --all-changes; echo "exit $?"
git status --short
```

The scan warns when `.clean-room-denylist` is missing or empty: report that as a FAIL of this gate. `git status` must not list `build/`, `.gradle/`, `.env`, `.clean-room-denylist`, scratch logs or `.claude/settings.local.json`, and should list only the files this change touched. A change you did not make is another session's: leave it and mention it.

## 7. Build

```bash
./gradlew build --console=plain > "$LOGS/gate_build.log" 2>&1; echo "exit $?"
```

Never run two Gradle builds in one checkout at the same time.

## After the gates

Observable over HTTP: `gateway-smoke`. A threshold, classifier or routing policy changed: `ai-eval`. Only report "green" when every applicable gate ran and passed; name any skipped gate and why.
