package io.github.pdroacordi.llmgateway.gateway.core.embedding

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Request and reply bytes in flight across the process (ADR-GW-05-011). Never waits: a call it cannot hold is refused,
 * so it is safe on an event loop.
 */
class EmbeddingReplyBudget(
    private val capacityBytes: Long,
) {
    private val used = AtomicLong()

    /** Null when [bytes] do not fit in what is left. */
    fun tryReserve(bytes: Long): Reservation? {
        while (true) {
            val current = used.get()
            if (bytes > capacityBytes - current) return null
            if (used.compareAndSet(current, current + bytes)) return Reservation(bytes)
        }
    }

    /**
     * A reply's [bytes] beside the [held] bytes of its own call. Null past the whole budget, as that reply could never
     * be carried. A reply that fits alone is trimmed by [held], so a call at the budget can still run alone.
     */
    fun tryReserveBeside(
        bytes: Long,
        held: Long,
    ): Reservation? = if (bytes > capacityBytes) null else tryReserve(minOf(bytes, capacityBytes - held))

    /** [release] can run from a cancel and a completion alike; only the first gives the bytes back. */
    inner class Reservation internal constructor(
        val bytes: Long,
    ) {
        private val released = AtomicBoolean()

        fun release() {
            if (released.compareAndSet(false, true)) used.addAndGet(-bytes)
        }
    }
}
