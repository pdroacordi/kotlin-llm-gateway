package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.StreamOutcome
import io.github.pdroacordi.llmgateway.gateway.core.chat.StreamSummary
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.time.Clock

/**
 * One line per stream, in the stream's coroutine, so MDC carries the request ID (GW-02 section 5, Observability). A
 * failure before `Started` is the mapper's to log, and a crash was logged with its stack trace where it was caught.
 */
internal class ChatStreamLog(
    private val clock: Clock,
    private val requestId: RequestId,
) {
    private val startedAt = clock.millis()

    @Volatile
    private var firstChunkAt: Long? = null

    /** The first chunk is in hand. */
    fun started() {
        firstChunkAt = clock.millis()
    }

    fun end(summary: StreamSummary) {
        when (val outcome = summary.outcome) {
            StreamOutcome.Completed -> {
                write(Level.DEBUG, "Stream completed", "completed", null, summary)
            }

            StreamOutcome.Cancelled -> {
                write(Level.INFO, "Stream cancelled", "cancelled", null, summary)
            }

            is StreamOutcome.Failed -> {
                if (summary.started) {
                    write(Level.ERROR, "Stream failed", "failed", outcome.failure.toReply(requestId).code, summary)
                }
            }

            StreamOutcome.Crashed -> {
                return
            }
        }
    }

    private fun write(
        level: Level,
        message: String,
        outcome: String,
        code: String?,
        summary: StreamSummary,
    ) {
        val now = clock.millis()
        log
            .atLevel(level)
            .setMessage(
                "$message: deployment={} outcome={} code={} chunks={} ttftMs={} durationMs={} " +
                    "inputTokens={} outputTokens={} estimated={}",
            ).addArgument(summary.deployment.id.value)
            .addArgument(outcome)
            .addArgument(code)
            .addArgument(summary.chunks)
            .addArgument(firstChunkAt?.let { it - startedAt })
            .addArgument(now - startedAt)
            .addArgument(summary.usage.inputTokens.value)
            .addArgument(summary.usage.outputTokens.value)
            .addArgument(summary.usage.estimated)
            .log()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ChatStreamLog::class.java)
    }
}
