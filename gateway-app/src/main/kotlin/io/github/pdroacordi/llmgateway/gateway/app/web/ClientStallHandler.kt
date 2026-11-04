package io.github.pdroacordi.llmgateway.gateway.app.web

import io.netty.channel.ChannelHandlerContext
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Closes a connection whose pending bytes made no progress for [timeout]: it detects a stalled socket, not a slow one
 * (ADR-GW-02-009, revised). One instance per HTTP request, as `doOnConnection` runs per request; the close runs the
 * normal cancel path.
 */
class ClientStallHandler(
    timeout: Duration,
    // observeOutput off: it reads a frame joining the queue as progress
    // (docs/knowledge/gotchas/netty-idle-observe-output-counts-queue-growth-as-progress.md).
) : IdleStateHandler(false, 0, timeout.toNanos() / TICKS, 0, TimeUnit.NANOSECONDS) {
    private val clock = StallClock(timeout.toNanos())

    override fun channelIdle(
        ctx: ChannelHandlerContext,
        event: IdleStateEvent,
    ) {
        if (event.state() != IdleState.WRITER_IDLE) return
        val buffer = ctx.channel().unsafe().outboundBuffer() ?: return
        val pending = buffer.totalPendingWriteBytes()
        val stalled =
            clock.stalled(
                System.nanoTime(),
                event.isFirst,
                System.identityHashCode(buffer.current()),
                buffer.currentProgress(),
                pending,
            )
        if (stalled) {
            log.info(
                "Closing connection {}: no write progress, {} bytes pending",
                ctx.channel().id().asShortText(),
                pending,
            )
            ctx.close()
        }
    }

    private companion object {
        // A stall is cut 1 to 1.5 timeouts after its last progress, inside the default idle-timeout of twice it.
        const val TICKS = 4
        private val log = LoggerFactory.getLogger(ClientStallHandler::class.java)
    }
}

/** The count behind [ClientStallHandler], one call per tick, with no socket or timer so each branch has a test. */
internal class StallClock(
    private val timeoutNanos: Long,
) {
    private var stalledSince = 0L
    private var lastHead = 0
    private var lastProgress = 0L

    /**
     * True once [pending] bytes have gone [timeoutNanos] with the kernel taking neither a whole write nor part of the
     * first message. [writeCompleted] stands beside [head], that message's identity, because Netty recycles buffers.
     */
    fun stalled(
        nowNanos: Long,
        writeCompleted: Boolean,
        head: Int,
        headProgress: Long,
        pending: Long,
    ): Boolean {
        val progressed = pending == 0L || writeCompleted || head != lastHead || headProgress != lastProgress
        lastHead = head
        lastProgress = headProgress
        if (progressed) stalledSince = nowNanos
        return !progressed && nowNanos - stalledSince >= timeoutNanos
    }
}
