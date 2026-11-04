package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.error.StreamErrorFrames
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.web.SseFrames
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.transform
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper

/**
 * Events to frames, one instance per stream. Until `Started` a failure is thrown, so nothing commits and the mapper
 * writes a status; after it a failure is a frame and the flow ends normally (ADR-GW-02-001, ADR-GW-02-007).
 */
internal class ChatStreamFrames(
    private val mapper: JsonMapper,
    private val errors: StreamErrorFrames,
    private val requestId: RequestId,
    private val includeUsage: Boolean,
    private val headers: HttpHeaders,
    private val log: ChatStreamLog,
) {
    private var started = false
    private var finished = false

    fun frames(events: Flow<ChatStreamEvent>): Flow<ByteArray> =
        events
            .transform { event ->
                when (event) {
                    is ChatStreamEvent.Started -> start(event)
                    is ChatStreamEvent.Chunk -> emit(frame(event.chunk))
                    is ChatStreamEvent.Completed -> emit(finish(SseFrames.DONE))
                    is ChatStreamEvent.Failed -> emit(failed(event))
                }
            }.catch { cause ->
                if (cause is CancellationException || !started) throw cause
                if (finished) errors.report(cause) else emit(errors.crash(cause, requestId))
            }

    // On the response, not the builder: an error clears only content headers, so these would survive onto it.
    private fun start(event: ChatStreamEvent.Started) {
        started = true
        log.started()
        headers.set(HttpHeaders.CONTENT_TYPE, CONTENT_TYPE)
        headers.set(HttpHeaders.CACHE_CONTROL, NO_CACHE)
        headers.set(GatewayHeaders.ACCEL_BUFFERING, NO_BUFFERING)
        headers.set(GatewayHeaders.DEPLOYMENT, event.deployment.id.value)
    }

    private fun frame(chunk: ChatChunk): ByteArray =
        SseFrames.data(mapper.writeValueAsBytes(ChatChunkWriter.write(chunk, includeUsage, mapper.nodeFactory)))

    private fun failed(event: ChatStreamEvent.Failed): ByteArray {
        if (!started) throw GatewayErrorException(event.failure)
        return finish(errors.frame(event.failure, requestId))
    }

    // Nothing follows a terminal frame: `[DONE]` means success, and a client reads one end per stream.
    private fun finish(frame: ByteArray): ByteArray {
        finished = true
        return frame
    }

    private companion object {
        const val CONTENT_TYPE = "text/event-stream;charset=UTF-8"
        const val NO_CACHE = "no-cache"
        const val NO_BUFFERING = "no"
    }
}
