package io.github.pdroacordi.llmgateway.mockprovider.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RequestJournalTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)
    private val journal = RequestJournal(capacity = 3, clock = clock)

    @Test
    fun `arrivals are numbered from 1 and counted in flight`() {
        val first = journal.arrive()
        val second = journal.arrive()

        assertEquals(1, first.seq)
        assertEquals(2, second.seq)
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), first.receivedAt)
        assertEquals(2, journal.stats().inFlight)
        journal.complete(first, entry(first.seq))
        assertEquals(1, journal.stats().inFlight)
        assertEquals(2, journal.stats().lastSeq)
    }

    @Test
    fun `the ring keeps the newest capacity entries and counts the dropped ones`() {
        repeat(5) {
            val arrival = journal.arrive()
            journal.complete(arrival, entry(arrival.seq))
        }

        assertEquals(listOf(3L, 4L, 5L), journal.query(JournalFilter()).map { it.seq })
        val stats = journal.stats()
        assertEquals(3, stats.journalSize)
        assertEquals(2, stats.dropped)
        assertEquals(3, stats.capacity)
    }

    @Test
    fun `entries are returned oldest first, whatever the completion order`() {
        val a = journal.arrive()
        val b = journal.arrive()
        journal.complete(b, entry(b.seq))
        journal.complete(a, entry(a.seq))

        assertEquals(listOf(1L, 2L), journal.query(JournalFilter()).map { it.seq })
    }

    @Test
    fun `filters by since, route, model, header and limit`() {
        val big = RequestJournal(capacity = 100, clock = clock)
        listOf(
            entry(0, route = "openai.chat", model = "a", headers = mapOf("x-client-request-id" to listOf("c-1"))),
            entry(0, route = "openai.chat", model = "b"),
            entry(
                0,
                route = "anthropic.messages",
                model = "a",
                headers = mapOf("x-client-request-id" to listOf("c-2")),
            ),
            entry(0, route = "unknown", model = null),
        ).forEach { template ->
            val arrival = big.arrive()
            big.complete(arrival, template.copy(seq = arrival.seq))
        }

        assertEquals(listOf(3L, 4L), big.query(JournalFilter(since = 2)).map { it.seq })
        assertEquals(listOf(1L, 2L), big.query(JournalFilter(route = "openai.chat")).map { it.seq })
        assertEquals(listOf(1L, 3L), big.query(JournalFilter(model = "a")).map { it.seq })
        assertEquals(
            listOf(3L),
            big.query(JournalFilter(headers = listOf("X-Client-Request-Id" to "c-2"))).map { it.seq },
        )
        assertEquals(listOf(1L), big.query(JournalFilter(limit = 1)).map { it.seq })
        assertEquals(
            emptyList<Long>(),
            big.query(JournalFilter(headers = listOf("x-client-request-id" to "c-1", "x-other" to "v"))).map { it.seq },
        )
    }

    @Test
    fun `counts per route, model and outcome`() {
        listOf(Outcome.Kind.COMPLETED, Outcome.Kind.COMPLETED, Outcome.Kind.ERROR_INJECTED).forEach { kind ->
            val arrival = journal.arrive()
            journal.complete(arrival, entry(arrival.seq, kind = kind))
        }

        val row = journal.stats().counts.single()
        assertEquals("openai.chat", row.route)
        assertEquals("mock-chat", row.model)
        assertEquals(3, row.requests)
        assertEquals(mapOf(Outcome.Kind.COMPLETED to 2L, Outcome.Kind.ERROR_INJECTED to 1L), row.outcomes)
    }

    @Test
    fun `reset clears entries and counts, keeps seq, and discards completions from before it`() {
        val before = journal.arrive()
        val done = journal.arrive()
        journal.complete(done, entry(done.seq))
        journal.reset()
        journal.complete(before, entry(before.seq))
        val after = journal.arrive()

        assertEquals(3, after.seq)
        assertEquals(emptyList<JournalEntry>(), journal.query(JournalFilter()))
        assertEquals(emptyList<CountRow>(), journal.stats().counts)
        assertEquals(1, journal.stats().inFlight)
        journal.complete(after, entry(after.seq))
        assertEquals(listOf(3L), journal.query(JournalFilter()).map { it.seq })
    }

    @Test
    fun `8 threads times 10 000 completions lose no count`() {
        val big = RequestJournal(capacity = 1000, clock = clock)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.execute {
                repeat(10_000) {
                    val arrival = big.arrive()
                    big.complete(arrival, entry(arrival.seq))
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        val stats = big.stats()
        assertEquals(80_000, stats.counts.single().requests)
        assertEquals(80_000, stats.lastSeq)
        assertEquals(0, stats.inFlight)
        assertEquals(79_000, stats.dropped)
        assertEquals(1000, big.query(JournalFilter(limit = 1000)).size)
    }

    @Test
    fun `a small ring under concurrent writes keeps exactly capacity distinct entries`() {
        val small = RequestJournal(capacity = 2, clock = clock)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.execute {
                repeat(5_000) {
                    val arrival = small.arrive()
                    small.complete(arrival, entry(arrival.seq))
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        val kept = small.query(JournalFilter())
        assertEquals(2, kept.size)
        assertEquals(2, kept.map { it.seq }.distinct().size)
        assertEquals(39_998, small.stats().dropped)
        assertEquals(
            40_000,
            small
                .stats()
                .counts
                .single()
                .requests,
        )
    }

    private fun entry(
        seq: Long,
        route: String = "openai.chat",
        model: String? = "mock-chat",
        headers: Map<String, List<String>> = emptyMap(),
        kind: Outcome.Kind = Outcome.Kind.COMPLETED,
    ) = JournalEntry(
        seq = seq,
        route = route,
        method = "POST",
        path = "/v1/chat/completions",
        model = model,
        stream = false,
        includeUsage = false,
        receivedAt = clock.instant(),
        durationMs = 1,
        headers = headers,
        credential = null,
        body = null,
        bodyTruncated = false,
        scenario = null,
        outcome = Outcome(kind = kind, status = 200),
    )
}
