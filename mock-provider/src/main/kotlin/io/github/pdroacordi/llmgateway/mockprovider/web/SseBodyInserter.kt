package io.github.pdroacordi.llmgateway.mockprovider.web

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactor.asFlux
import org.springframework.http.ReactiveHttpOutputMessage
import org.springframework.web.reactive.function.BodyInserter
import reactor.core.publisher.Mono

/**
 * Writes each string of [frames] as its own inner publisher of `writeAndFlushWith`, which flushes after each one, so
 * every SSE event reaches the socket when it is produced (ADR-FND-03-007,
 * docs/knowledge/patterns/raw-sse-frames-flushed-per-event.md). Also used for the non-stream `disconnect` fault.
 */
class SseBodyInserter(
    private val frames: Flow<String>,
) : BodyInserter<Flow<String>, ReactiveHttpOutputMessage> {
    override fun insert(
        outputMessage: ReactiveHttpOutputMessage,
        context: BodyInserter.Context,
    ): Mono<Void> {
        val buffers = outputMessage.bufferFactory()
        return outputMessage.writeAndFlushWith(
            frames.asFlux().map { frame -> Mono.fromSupplier { buffers.wrap(frame.encodeToByteArray()) } },
        )
    }
}
