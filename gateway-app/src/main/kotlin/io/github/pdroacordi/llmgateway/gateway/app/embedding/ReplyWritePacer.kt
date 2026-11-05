package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.MonoSink
import reactor.core.scheduler.Schedulers
import reactor.netty.Connection
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds each item of a reply back until the channel is writable. Demand cannot do this: `MonoSendMany` asks for 128
 * items at once and `sendGroups` feeds it the same way (ADR-GW-05-013). One instance a reply. The source runs on the
 * channel's event loop, where a send writes at once, so the check after an item sees that item's bytes.
 */
internal class ReplyWritePacer(
    private val connection: Connection,
) : ChannelInboundHandlerAdapter() {
    private val waiting = AtomicReference<MonoSink<Unit>?>()

    /** Reactor Netty removes the handler when the response ends, so the next one on a connection adds its own. */
    fun <T : Any> pace(items: Flux<T>): Flux<T> {
        val channel = connection.channel()
        return Flux
            .defer {
                connection.addHandlerLast(HANDLER, this)
                items.concatMap({ item -> writable(channel).thenReturn(item) }, 1)
            }.subscribeOn(Schedulers.fromExecutor(channel.eventLoop()))
    }

    private fun writable(channel: Channel): Mono<Unit> =
        Mono.create { sink ->
            waiting.set(sink)
            if (channel.isWritable || !channel.isActive) wake()
        }

    private fun wake() {
        waiting.getAndSet(null)?.success(Unit)
    }

    override fun channelWritabilityChanged(ctx: ChannelHandlerContext) {
        if (ctx.channel().isWritable) wake()
        ctx.fireChannelWritabilityChanged()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        wake()
        ctx.fireChannelInactive()
    }

    private companion object {
        const val HANDLER = "llmgw.replyWritePacer"
    }
}
