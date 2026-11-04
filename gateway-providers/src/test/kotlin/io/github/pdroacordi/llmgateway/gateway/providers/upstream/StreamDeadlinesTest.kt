package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class StreamDeadlinesTest {
    private class ManualClock : Clock() {
        var now: Long = START

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val clock = ManualClock()

    private fun deadlines(
        first: Long = 120_000,
        idle: Long = 60_000,
        total: Long = 1_800_000,
    ): StreamDeadlines =
        StreamDeadlines(
            StreamConfig(
                firstByteTimeout = Duration.ofMillis(first),
                idleTimeout = Duration.ofMillis(idle),
                totalTimeout = Duration.ofMillis(total),
                maxEventBytes = 1024,
            ),
            clock,
            START,
        )

    @Test
    fun `the first item has the first-byte timeout`() {
        assertEquals(Duration.ofSeconds(120), deadlines().first())
    }

    @Test
    fun `the first item is capped by the total`() {
        assertEquals(Duration.ofSeconds(10), deadlines(total = 10_000).first())
    }

    @Test
    fun `the next item has the idle timeout`() {
        assertEquals(Duration.ofSeconds(60), deadlines().next(draining = false))
    }

    @Test
    fun `the next item is capped by what is left of the total`() {
        val deadlines = deadlines(idle = 60_000, total = 100_000)

        clock.now = START + 70_000

        assertEquals(Duration.ofSeconds(30), deadlines.next(draining = false))
    }

    @Test
    fun `past the total the next item has no time left`() {
        val deadlines = deadlines(total = 100_000)

        clock.now = START + 120_000

        assertEquals(Duration.ZERO, deadlines.next(draining = false))
    }

    @Test
    fun `after Completed the next item has the drain`() {
        assertEquals(Duration.ofSeconds(1), deadlines().next(draining = true))
    }

    @Test
    fun `the drain ignores what is left of the total, as nothing more can fail`() {
        val deadlines = deadlines(total = 100_000)

        clock.now = START + 100_000

        assertEquals(Duration.ofSeconds(1), deadlines.next(draining = true))
    }

    @Test
    fun `the drain does not restart with each read`() {
        val deadlines = deadlines()
        deadlines.next(draining = true)

        clock.now = START + 400

        assertEquals(Duration.ofMillis(600), deadlines.next(draining = true))

        clock.now = START + 2_000

        assertEquals(Duration.ZERO, deadlines.next(draining = true))
    }

    private companion object {
        const val START = 1_793_750_400_000L
    }
}
