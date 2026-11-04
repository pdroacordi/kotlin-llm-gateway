package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.app.web.SseFrames
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** A failure after commit as one SSE frame with the ADR-015 envelope; only this package writes an error body. */
internal class StreamErrorFrames(
    private val mapper: JsonMapper,
) {
    fun frame(
        error: GatewayError,
        requestId: RequestId,
    ): ByteArray {
        val envelope = OpenAiErrorEnvelope.of(error.toReply(requestId)).error
        // By hand: the mapper's first bean serializer would be built on an event loop, under a lock.
        val body = mapper.nodeFactory.objectNode()
        val member = body.putObject("error")
        member.put("message", envelope.message)
        member.put("type", envelope.type)
        member.put("param", envelope.param)
        member.put("code", envelope.code)
        return SseFrames.data(mapper.writeValueAsBytes(body))
    }

    /** A bug after commit: the one ERROR line, then the `internal_error` frame. */
    fun crash(
        cause: Throwable,
        requestId: RequestId,
    ): ByteArray {
        report(cause)
        return frame(GatewayError.Internal, requestId)
    }

    /** A bug after the terminal frame: the client has its answer, so nothing more is written. */
    fun report(cause: Throwable) {
        log.error("Stream crashed after commit", LoggableFailure.of(cause))
    }

    private companion object {
        private val log = LoggerFactory.getLogger(StreamErrorFrames::class.java)
    }
}
