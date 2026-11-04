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
 * (ADR-GW-02-009, revised). One instance per channel; the close runs the normal cancel path.
 */
class ClientStallHandler(
    timeout: Duration,
) : IdleStateHandler(true, 0, timeout.toNanos() / TICKS, 0, TimeUnit.NANOSECONDS) {
    private val timeoutNanos = timeout.toNanos()
    private val tickNanos = timeoutNanos / TICKS
    private var stalledSince = 0L
    private var lastEvent = 0L

    // observeOutput keeps a tick that saw progress silent. A first event only says no write completed in a tick, so it
    // starts the count, and so does a gap in the events: a silent tick came in between.
    override fun channelIdle(
        ctx: ChannelHandlerContext,
        event: IdleStateEvent,
    ) {
        if (event.state() != IdleState.WRITER_IDLE) return
        val now = System.nanoTime()
        val continued = !event.isFirst && now - lastEvent <= tickNanos + tickNanos / 2
        if (!continued) stalledSince = now
        lastEvent = now
        val pending =
            ctx
                .channel()
                .unsafe()
                .outboundBuffer()
                ?.totalPendingWriteBytes() ?: 0
        if (pending > 0 && now - stalledSince >= timeoutNanos) {
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
