package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorCodes
import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorReply
import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorTypes
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.core.Ordered
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.codec.HttpMessageWriter
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.DisconnectedClientHelper
import reactor.core.publisher.Mono
import java.util.Optional

/**
 * The only exception mapper: everything that escapes a filter or handler before the response is committed becomes
 * the OpenAI envelope (ADR-FND-04-005, docs/knowledge/patterns/one-error-funnel.md). It replaces Spring Boot's
 * default handler, which backs off when any [ErrorWebExceptionHandler] bean exists.
 */
class GatewayErrorWebExceptionHandler(
    private val codecs: ServerCodecConfigurer,
    private val ids: RequestIdGenerator,
) : ErrorWebExceptionHandler,
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun handle(
        exchange: ServerWebExchange,
        ex: Throwable,
    ): Mono<Void> {
        val requestId = (exchange.attributes[RequestContext.ATTRIBUTE] as? RequestContext)?.requestId ?: ids.next()
        // The helper matches phrases anywhere in the cause chain, so it only lowers the log level and never skips
        // the write (docs/knowledge/gotchas/spring-disconnected-client-helper-matches-phrases-in-cause-chain.md).
        val looksDisconnected = DisconnectedClientHelper.isClientDisconnectedException(ex)
        if (!exchange.response.isCommitted) return writeReply(exchange, requestId, ex, looksDisconnected)

        // Status and headers are already sent; GW-02 turns stream failures into an SSE error event first.
        withMdc(requestId) {
            if (looksDisconnected) {
                log.debug("Client disconnected after the response was committed")
            } else {
                log.error("Request failed after the response was committed", LoggableFailure.of(ex))
            }
        }
        return Mono.error(ex)
    }

    private fun writeReply(
        exchange: ServerWebExchange,
        requestId: RequestId,
        ex: Throwable,
        looksDisconnected: Boolean,
    ): Mono<Void> {
        val request = exchange.request
        val reply = ex.toGatewayError(request.method.name(), request.path.value()).toReply(requestId)
        logReply(reply, requestId, ex, looksDisconnected)
        val response = exchange.response
        prepare(response, reply.status, requestId)
        reply.headers.forEach { (name, value) -> response.headers.set(name, value) }
        return BodyInserters
            .fromValue(OpenAiErrorEnvelope.of(reply))
            .insert(response, WriterContext(request))
            .onErrorResume { failure ->
                if (DisconnectedClientHelper.isClientDisconnectedException(failure)) {
                    withMdc(requestId) { log.debug("Client disconnected while the error envelope was written") }
                    Mono.empty()
                } else {
                    writeFallback(response, requestId, reply, failure)
                }
            }
    }

    // The guarantee "every error is an envelope" must not depend on the codecs.
    private fun writeFallback(
        response: ServerHttpResponse,
        requestId: RequestId,
        reply: ErrorReply,
        failure: Throwable,
    ): Mono<Void> {
        withMdc(requestId) {
            log.error("Writing the error envelope failed; writing the constant 500 body", LoggableFailure.of(failure))
        }
        if (response.isCommitted) return Mono.error(failure)
        reply.headers.keys.forEach { response.headers.remove(it) }
        prepare(response, GatewayError.Internal.toReply(requestId).status, requestId)
        return response.writeWith(Mono.fromSupplier { response.bufferFactory().wrap(fallbackBody(requestId)) })
    }

    private fun prepare(
        response: ServerHttpResponse,
        status: Int,
        requestId: RequestId,
    ) {
        response.setStatusCode(HttpStatusCode.valueOf(status))
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.set(GatewayHeaders.REQUEST_ID, requestId.toString())
    }

    // 4xx messages are never logged: a decoding error can quote request bytes.
    private fun logReply(
        reply: ErrorReply,
        requestId: RequestId,
        ex: Throwable,
        looksDisconnected: Boolean,
    ) = withMdc(requestId) {
        when {
            reply.status < SERVER_ERROR -> {
                log.debug("Request rejected with status {} ({})", reply.status, reply.code)
            }

            looksDisconnected -> {
                log.debug(
                    "Request failed with status {} ({}); the client may have disconnected",
                    reply.status,
                    reply.code,
                )
            }

            else -> {
                log.error("Request failed with status {} ({})", reply.status, reply.code, LoggableFailure.of(ex))
            }
        }
    }

    // The mapper runs outside the filter's coroutine, so MDCContext does not apply here.
    private fun withMdc(
        requestId: RequestId,
        block: () -> Unit,
    ) = MDC.putCloseable(GatewayHeaders.MDC_REQUEST_ID, requestId.toString()).use { block() }

    private inner class WriterContext(
        private val request: ServerHttpRequest,
    ) : BodyInserter.Context {
        override fun messageWriters(): List<HttpMessageWriter<*>> = codecs.writers

        override fun serverRequest(): Optional<ServerHttpRequest> = Optional.of(request)

        override fun hints(): Map<String, Any> = emptyMap()
    }

    private companion object {
        // Before Boot's default handler (-1) and WebFlux's status handler (0), should either still be registered.
        const val ORDER = -2
        const val SERVER_ERROR = 500
        private val log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler::class.java)

        // The message holds only fixed text and the request ID's hex and dashes, so it needs no JSON escaping.
        fun fallbackBody(requestId: RequestId): ByteArray =
            (
                """{"error":{"message":"${GatewayError.Internal.toReply(requestId).message}",""" +
                    """"type":"${ErrorTypes.SERVER_ERROR}","param":null,"code":"${ErrorCodes.INTERNAL_ERROR}"}}"""
            ).encodeToByteArray()
    }
}
