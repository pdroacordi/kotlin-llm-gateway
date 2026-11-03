package io.github.pdroacordi.llmgateway.mockprovider.error

import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBodies
import io.github.pdroacordi.llmgateway.mockprovider.web.RequestRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.core.Ordered
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The mock's only exception mapper (ARCH-10, docs/knowledge/patterns/one-error-funnel.md): framework failures,
 * rejections and bugs become the route's provider error shape, Anthropic under `/v1/messages`, OpenAI elsewhere.
 * Injected errors are data, written by `Serving`, and never pass through here.
 */
class MockErrorWebExceptionHandler(
    private val bodies: ErrorBodies,
) : ErrorWebExceptionHandler,
    Ordered {
    override fun getOrder(): Int = ORDER

    override fun handle(
        exchange: ServerWebExchange,
        ex: Throwable,
    ): Mono<Void> {
        val response = exchange.response
        if (response.isCommitted) return Mono.error(abortAfterCommit(exchange, ex))

        val request = exchange.request
        val path = request.path.value()
        val reply = ex.toMockReply(request.method.name(), path)
        if (reply.status >= SERVER_ERROR) {
            log.error("Request failed with status {}", reply.status, LoggableFailure.of(ex))
        } else {
            log.debug("Request rejected with status {}", reply.status)
        }
        val api = ProviderApi.forPath(path)
        val requestId = RequestRecord.of(exchange)?.requestId ?: UNCOUNTED_REQUEST_ID
        val body = ErrorBodies.of(api, reply.status, reply.message, reply.code, reply.param)
        response.statusCode = HttpStatusCode.valueOf(reply.status)
        response.headers.contentType = MediaType.APPLICATION_JSON
        reply.headers.forEach { (name, value) -> response.headers.set(name, value) }
        val bytes = bodies.render(api, body, requestId).encodeToByteArray()
        return response
            .writeWith(Mono.fromSupplier { response.bufferFactory().wrap(bytes) })
            .onErrorResume { failure ->
                log.debug("Writing the error body failed: {}", failure.javaClass.simpleName)
                Mono.empty()
            }
    }

    private fun abortAfterCommit(
        exchange: ServerWebExchange,
        ex: Throwable,
    ): MockConnectionAbort {
        // An intentional abort (`disconnect`, a `stall` at max-hold) is decided by type, never by phrases in a cause.
        if (ex is MockConnectionAbort) {
            log.debug("Response aborted after commit: {}", ex.javaClass.simpleName)
            return ex
        }
        log.error("Request failed after the response was committed", LoggableFailure.of(ex))
        // Reactor Netty closes the connection without the terminating chunk and logs what it gets: never `ex`, whose
        // checkpoint names the raw query
        // (docs/knowledge/gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md).
        val requestId = RequestRecord.of(exchange)?.requestId ?: UNCOUNTED_REQUEST_ID
        return MockConnectionAbort("Aborted after commit, request $requestId")
    }

    private companion object {
        // Before Boot's default handler (-1) and WebFlux's status handler (0).
        const val ORDER = -2
        const val SERVER_ERROR = 500

        // Admin and actuator paths are not counted, so they have no provider request ID.
        const val UNCOUNTED_REQUEST_ID = "req_none"
        private val log = LoggerFactory.getLogger(MockErrorWebExceptionHandler::class.java)
    }
}
