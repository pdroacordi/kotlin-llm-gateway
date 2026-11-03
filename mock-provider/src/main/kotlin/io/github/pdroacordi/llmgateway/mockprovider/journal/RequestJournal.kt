package io.github.pdroacordi.llmgateway.mockprovider.journal

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder

/** A request's place in the journal: its arrival sequence and the epoch it arrived in. */
data class Arrival(
    val seq: Long,
    val epoch: Int,
    val receivedAt: Instant,
)

/**
 * The arrival counter, per-(route, model, outcome) counts and a bounded ring of entries, all lock-free
 * (ADR-FND-03-008). `reset` swaps in a new epoch; a completion from an older epoch is dropped, so nothing that
 * started before a reset appears after it. The sequence is never rewound.
 */
class RequestJournal(
    private val capacity: Int,
    private val clock: Clock,
) {
    private val seq = AtomicLong(0)
    private val inFlight = AtomicInteger(0)
    private val state = AtomicReference(State(0, capacity))

    init {
        require(capacity >= 1) { "capacity must be at least 1" }
    }

    fun arrive(): Arrival {
        inFlight.incrementAndGet()
        // The epoch is read before the sequence is taken: an arrival that races a reset belongs to the old epoch and
        // is dropped, so no entry numbered at or below the `lastSeq` a reset left can appear after it.
        val epoch = state.get().epoch
        return Arrival(seq.incrementAndGet(), epoch, clock.instant())
    }

    fun complete(
        arrival: Arrival,
        entry: JournalEntry,
    ) {
        // Read once: a reset after this line writes into the discarded state, never into the new one.
        val current = state.get()
        if (current.epoch == arrival.epoch) {
            // Entry first, then its count: `stats` never counts an entry `requests` cannot show yet.
            current.ring.add(entry)
            current.count(entry.route, entry.model, entry.outcome.kind)
        }
        // Last: a caller that waits for inFlight to drop then finds the entry.
        inFlight.decrementAndGet()
    }

    fun query(filter: JournalFilter): List<JournalEntry> =
        state
            .get()
            .ring
            .snapshot()
            .filter(filter::matches)
            .take(filter.limit)

    fun stats(): JournalStats {
        val current = state.get()
        return JournalStats(
            lastSeq = seq.get(),
            inFlight = inFlight.get(),
            counts = current.rows(),
            capacity = capacity,
            journalSize = current.ring.size(),
            dropped = current.ring.dropped(),
        )
    }

    fun reset() {
        state.updateAndGet { State(it.epoch + 1, capacity) }
    }

    private data class CountKey(
        val route: String,
        val model: String?,
        val kind: Outcome.Kind,
    )

    private class State(
        val epoch: Int,
        capacity: Int,
    ) {
        val ring = Ring(capacity)
        private val counts = ConcurrentHashMap<CountKey, LongAdder>()

        fun count(
            route: String,
            model: String?,
            kind: Outcome.Kind,
        ) = counts.computeIfAbsent(CountKey(route, model, kind)) { LongAdder() }.increment()

        fun rows(): List<CountRow> =
            counts.entries
                .groupBy({ it.key.route to it.key.model }, { it.key.kind to it.value.sum() })
                .map { (key, outcomes) ->
                    CountRow(key.first, key.second, outcomes.sumOf { it.second }, outcomes.toMap())
                }.sortedWith(compareBy({ it.route }, { it.model.orEmpty() }))
    }

    private class Slot(
        val write: Long,
        val entry: JournalEntry,
    )

    /**
     * Slot = write number mod capacity; the oldest entries are overwritten. Two writers a capacity apart can reach the
     * same slot out of order, so a slot keeps the higher write number and a late older write is discarded.
     */
    private class Ring(
        private val capacity: Int,
    ) {
        private val slots = AtomicReferenceArray<Slot?>(capacity)
        private val writes = AtomicLong(0)

        fun add(entry: JournalEntry) {
            val write = writes.getAndIncrement()
            val slot = Slot(write, entry)
            slots.getAndUpdate((write % capacity).toInt()) { current ->
                if (current == null || current.write < write) slot else current
            }
        }

        fun snapshot(): List<JournalEntry> = List(capacity) { slots.get(it)?.entry }.filterNotNull().sortedBy { it.seq }

        fun size(): Int = minOf(writes.get(), capacity.toLong()).toInt()

        fun dropped(): Long = maxOf(writes.get() - capacity, 0)
    }
}
