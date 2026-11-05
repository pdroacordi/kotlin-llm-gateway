package io.github.pdroacordi.llmgateway.gateway.core.embedding

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class EmbeddingReplyBudgetTest {
    @Test
    fun `a reservation of the whole budget succeeds when it is free, so one large call can run alone`() {
        assertNotNull(EmbeddingReplyBudget(100).tryReserve(100))
    }

    @Test
    fun `a reply that fits the budget alone is trimmed by what its call holds, so it can still run`() {
        val budget = EmbeddingReplyBudget(100)
        val held = checkNotNull(budget.tryReserve(10))

        val reply = checkNotNull(budget.tryReserveBeside(100, held.bytes))

        assertEquals(90, reply.bytes)
    }

    @Test
    fun `a reply over the whole budget is refused, even on a free budget`() {
        assertNull(EmbeddingReplyBudget(100).tryReserveBeside(101, 0))
    }

    @Test
    fun `a reply is refused when other calls hold what it needs`() {
        val budget = EmbeddingReplyBudget(100)
        budget.tryReserve(60)

        assertNull(budget.tryReserveBeside(41, 0))
    }

    @Test
    fun `a reservation past what is left is refused, and takes nothing`() {
        val budget = EmbeddingReplyBudget(100)
        budget.tryReserve(60)

        assertNull(budget.tryReserve(41))
        assertNotNull(budget.tryReserve(40))
    }

    @Test
    fun `a release gives the bytes back`() {
        val budget = EmbeddingReplyBudget(100)
        val first = checkNotNull(budget.tryReserve(100))

        first.release()

        assertNotNull(budget.tryReserve(100))
    }

    @Test
    fun `a second release of one reservation gives nothing back`() {
        val budget = EmbeddingReplyBudget(100)
        val first = checkNotNull(budget.tryReserve(60))
        budget.tryReserve(40)

        first.release()
        first.release()

        assertNull(budget.tryReserve(61))
    }

    @Test
    fun `concurrent reservations never pass the capacity`() {
        val budget = EmbeddingReplyBudget(10)
        val pool = Executors.newFixedThreadPool(THREADS)

        val granted =
            try {
                pool
                    .invokeAll(List(ATTEMPTS) { Callable { budget.tryReserve(1) } })
                    .count { it.get() != null }
            } finally {
                pool.shutdown()
            }

        assertEquals(10, granted)
    }

    private companion object {
        const val THREADS = 8
        const val ATTEMPTS = 1000
    }
}
