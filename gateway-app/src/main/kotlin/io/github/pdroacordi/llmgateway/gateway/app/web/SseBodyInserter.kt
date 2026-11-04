package io.github.pdroacordi.llmgateway.gateway.app.web

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.HttpHeaders
import org.springframework.http.ReactiveHttpOutputMessage
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.util.DisconnectedClientHelper
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import kotlin.coroutines.CoroutineContext

/**
 * One flushed frame per element of the flow [frames] builds around the response's headers, mutable until the first
 * frame (ADR-GW-02-001). [context] holds `RequestContext` and MDC, and no `Job`: the handler returns before the write.
 */
class SseBodyInserter(
    private val context: CoroutineContext,
    private val frames: (HttpHeaders) -> Flow<ByteArray>,
) : BodyInserter<Unit, ReactiveHttpOutputMessage> {
    override fun insert(
        outputMessage: ReactiveHttpOutputMessage,
        context: BodyInserter.Context,
    ): Mono<Void> {
        val buffers = outputMessage.bufferFactory()
        val finished = Sinks.empty<Void>()
        val source = frames(outputMessage.headers).asFlux(this.context).takeUntilOther(finished.asMono())
        return outputMessage
            // The inner Mono wraps late, so a cancelled queue holds arrays, never buffers.
            .writeAndFlushWith(
                source.map { frame -> Mono.fromSupplier { buffers.wrap(frame) } },
            )
            // Only a write can fail here once committed; the flow's own failures became frames
            // (docs/knowledge/gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md).
            .onErrorResume({
                outputMessage.isCommitted && DisconnectedClientHelper.isClientDisconnectedException(it)
            }) { Mono.empty() }
            // ChannelSendOperator keeps its source when the write fails at once, and the flow owns the upstream call
            // (docs/knowledge/gotchas/spring-channel-send-operator-keeps-source-when-the-write-fails-at-once.md).
            .doFinally { finished.tryEmitEmpty() }
    }
}
