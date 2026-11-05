---
id: bugs/embedding-request-memory-is-outside-the-budget
type: bug
title: Request bodies, their trees and the upstream body are not in the embedding budget, so 512 MB is not safe on a 2 GiB heap
status: active
severity: high
tags: [embeddings, memory, gw-05]
related: [bugs/embedding-max-connections-does-not-cap-reply-memory, bugs/embedding-request-of-10mb-holds-the-event-loop]
created: 2026-11-05
updated: 2026-11-05
source_files:
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingAdmission.kt
  - gateway-app/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/app/embedding/EmbeddingHandler.kt
  - gateway-core/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/core/embedding/EmbeddingReplyLimits.kt
  - gateway-providers/src/main/kotlin/io/github/pdroacordi/llmgateway/gateway/providers/upstream/CpuLane.kt
---

## Summary

ADR-GW-05-011 says the budget's 1 GiB of heap leaves 1 GiB for chat and the JVM on a 2 GiB heap. A reservation counts only the reply, so a team with a valid key can use that second GiB with small reservations, or none, and an OOM takes chat down.

## Symptom

Found in review, by reading and arithmetic; nothing was run, as a smoke run held the apps. The live tree size is an estimate.

- **Queued parses, no reservation.** `CpuLane.kt:25` has an unbounded queue, drained at about 10 parses a second by the spec's own figures. Each waiting parse holds its 10 MB body, so 100 concurrent requests hold about 1 GiB. The spec's bound, "the connection count", has no configured limit.
- **Running parses, no reservation.** ADR-GW-05-009 measured 720 MB allocated for `[[1],...]` at 10 MB. Live at peak is about 350 MB by estimate (2.4 million `ArrayNode`s, each with an `ArrayList`, plus the typed `LongArray`s), so about 1.4 GB across 4 lane threads.
- **Admitted calls, tiny reservation.** One 10 MB `input` string reserves 99 KiB, yet holds the `String` (`EmbeddingService` keeps it for the estimate) and the outbound array, about 20 MB, for up to `request-timeout`. 64 in flight is about 1.25 GiB against 6.2 MiB of reservations, and 128 more can wait 5 s for a slot.

The spec calls this memory "before admission"; the third case is after it. ADR-GW-05-006's "peak per call is 2N" leaves out all three.

## Root cause

`EmbeddingAdmission.kt:30` reserves `worstCaseReplyBytes`, a function of the reply only, and does so after the parse (`EmbeddingHandler.kt:33-37`).

The heap the sizing assumes is also unguarded. ADR-GW-05-011 cites 2 GiB as "the figure ADR-GW-05-006 used", but ADR-GW-05-006 no longer states one, and no `-Xmx` or check exists in `deploy/` or the app. A default JVM in a 2 GiB container has a 512 MiB heap, below the budget's own 1 GiB.

## Fix

Fixed in GW-05 (Roadmap: GW-05). `EmbeddingAdmission.holdBody` (`EmbeddingAdmission.kt:26`) reserves 2 x the body length before `cpu.run`, so queued parses are counted, and `ReplyHold` holds it with the reply's bytes to the end of the write, each released once. A bounded lane queue caps the waiting tasks too. `max-in-flight-bytes` follows the heap: unset is 25% of `Runtime.maxMemory()`, and a set value above 50% stops the start (`OpenAiProperties.kt:100`). ADR-GW-05-006 and -011 state the arithmetic and the heap.

Still outside the budget, as ADR-GW-05-011 states: a body while it is read, and the tree of a running parse. The second is at most `cpu-threads` trees, 1.4 GB by estimate, so a 2 GiB heap does not hold four worst-case parses at once and a 4 GiB heap does. Nothing here was measured (OPS-06).

Red before the fix: a 40 KB unparsable body on a 64 KB budget gave 400, not 503; a second held call hung instead of being refused (`EmbeddingBudgetTest`).

## Prevention

Add to smoke 6: 8 concurrent 10 MB `[[1],...]` requests, and 64 one-string 10 MB requests against a `hang` scenario, on the stated heap.
