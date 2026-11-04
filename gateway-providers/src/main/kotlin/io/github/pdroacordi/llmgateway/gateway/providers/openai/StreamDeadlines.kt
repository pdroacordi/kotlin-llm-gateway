package io.github.pdroacordi.llmgateway.gateway.providers.openai

import java.time.Clock
import java.time.Duration

/** How long the next upstream item may take: the whole policy of ADR-GW-02-006. [startedAt] is in epoch ms. */
internal class StreamDeadlines(
    private val config: StreamConfig,
    private val clock: Clock,
    private val startedAt: Long,
) {
    private var drainEnd: Long? = null

    fun first(): Duration = minOf(config.firstByteTimeout, config.totalTimeout)

    fun next(draining: Boolean): Duration = if (draining) drainLeft() else minOf(config.idleTimeout, totalLeft())

    private fun totalLeft(): Duration =
        config.totalTimeout.minusMillis(clock.millis() - startedAt).coerceAtLeast(Duration.ZERO)

    // From the first call, so a provider that dribbles after `[DONE]` cannot restart it.
    private fun drainLeft(): Duration {
        val now = clock.millis()
        val end = drainEnd ?: (now + DRAIN.toMillis()).also { drainEnd = it }
        return Duration.ofMillis((end - now).coerceAtLeast(0))
    }

    private companion object {
        val DRAIN: Duration = Duration.ofSeconds(1)
    }
}
