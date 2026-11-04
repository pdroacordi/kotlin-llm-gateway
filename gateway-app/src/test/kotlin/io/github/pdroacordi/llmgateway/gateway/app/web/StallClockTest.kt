package io.github.pdroacordi.llmgateway.gateway.app.web

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StallClockTest {
    private val clock = StallClock(TIMEOUT)
    private var now = 1_000L

    private fun tick(
        writeCompleted: Boolean = false,
        head: Int = HEAD,
        progress: Long = 0,
        pending: Long = PENDING,
    ): Boolean {
        now += TICK
        return clock.stalled(now, writeCompleted, head, progress, pending)
    }

    private fun assertCutAfterATimeout() {
        repeat(TICKS - 1) { assertFalse(tick(), "tick $it") }
        assertTrue(tick())
    }

    @BeforeEach
    fun firstEvent() {
        assertFalse(tick(writeCompleted = true))
    }

    @Test
    fun `a queue that does not move for the timeout is a stall`() {
        assertCutAfterATimeout()
    }

    @Test
    fun `frames joining the queue are not progress`() {
        repeat(TICKS - 1) { assertFalse(tick(pending = PENDING + it + 1), "tick $it") }
        assertTrue(tick(pending = PENDING + TICKS))
    }

    @Test
    fun `the kernel taking part of the first message restarts the count`() {
        repeat(TICKS - 1) { tick() }

        assertFalse(tick(progress = 1))

        repeat(TICKS - 1) { assertFalse(tick(progress = 1), "tick $it") }
        assertTrue(tick(progress = 1))
    }

    @Test
    fun `a new first message restarts the count`() {
        repeat(TICKS - 1) { tick() }

        assertFalse(tick(head = HEAD + 1))

        repeat(TICKS - 1) { assertFalse(tick(head = HEAD + 1), "tick $it") }
        assertTrue(tick(head = HEAD + 1))
    }

    @Test
    fun `a completed write restarts the count, though a recycled buffer leaves the first message looking the same`() {
        repeat(TICKS - 1) { tick() }

        assertFalse(tick(writeCompleted = true))

        assertCutAfterATimeout()
    }

    @Test
    fun `with nothing pending it never is a stall, and that time does not count`() {
        repeat(TICKS * 2) { assertFalse(tick(head = 0, pending = 0), "tick $it") }

        repeat(TICKS - 1) { assertFalse(tick(head = 0), "tick $it") }
        assertTrue(tick(head = 0))
    }

    private companion object {
        const val TICKS = 4
        const val TICK = 250L
        const val TIMEOUT = TICKS * TICK
        const val HEAD = 7
        const val PENDING = 100L
    }
}
