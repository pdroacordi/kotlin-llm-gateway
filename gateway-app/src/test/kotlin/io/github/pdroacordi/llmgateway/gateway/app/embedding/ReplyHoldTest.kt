package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingReplyBudget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReplyHoldTest {
    private val budget = EmbeddingReplyBudget(100)

    private fun reserve(bytes: Long) = checkNotNull(budget.tryReserve(bytes))

    @Test
    fun `the end of a call gives back the body's bytes and the reply's`() {
        val hold = ReplyHold()
        hold.keep(reserve(60))
        hold.keep(reserve(40))
        assertNull(budget.tryReserve(1))

        hold.end()

        assertNotNull(budget.tryReserve(100))
    }

    @Test
    fun `a second end gives nothing back twice`() {
        val hold = ReplyHold()
        hold.keep(reserve(60))
        hold.keep(reserve(40))

        hold.end()
        hold.end()
        reserve(100)

        assertNull(budget.tryReserve(1))
    }

    @Test
    fun `a reservation kept after the end, as a cancel that races the handler does, goes back at once`() {
        val hold = ReplyHold()
        hold.keep(reserve(60))
        hold.end()

        hold.keep(reserve(100))

        assertNotNull(budget.tryReserve(100))
    }

    @Test
    fun `what a hold keeps is counted, for the reply's reservation to fit beside it`() {
        val hold = ReplyHold()
        hold.keep(reserve(60))

        assertEquals(60, hold.heldBytes)
    }
}
