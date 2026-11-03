package io.github.pdroacordi.llmgateway.gateway.app.request

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.request.ClientRequestId
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

/**
 * The first filter: assigns the request ID, validates `x-client-request-id` and runs the rest of the chain inside
 * [RequestContext] and its MDC copy (ADR-FND-04-001, ADR-FND-04-003).
 */
class RequestContextWebFilter(
    private val ids: RequestIdGenerator,
) : CoWebFilter(),
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        val requestId = ids.next()
        val responseHeaders = exchange.response.headers
        // Set before validation, so the 400 for a bad client ID carries it too.
        responseHeaders.set(GatewayHeaders.REQUEST_ID, requestId.toString())
        exchange.attributes[RequestContext.ATTRIBUTE] = RequestContext(requestId, null)

        val clientRequestId = clientRequestId(exchange.request.headers)
        val context = RequestContext(requestId, clientRequestId)
        exchange.attributes[RequestContext.ATTRIBUTE] = context
        clientRequestId?.let { responseHeaders.set(GatewayHeaders.CLIENT_REQUEST_ID, it.value) }

        val mdc = MDC.getCopyOfContextMap().orEmpty() + mdcEntries(context)
        withContext(context + MDCContext(mdc)) {
            chain.filter(exchange)
        }
    }

    private fun clientRequestId(headers: HttpHeaders): ClientRequestId? {
        val values = headers[GatewayHeaders.CLIENT_REQUEST_ID].orEmpty()
        return when {
            values.isEmpty() -> {
                null
            }

            values.size > 1 -> {
                throw GatewayErrorException(GatewayError.InvalidRequest.invalidClientRequestId)
            }

            values.single().isEmpty() -> {
                null
            }

            else -> {
                ClientRequestId.parseOrNull(values.single())
                    ?: throw GatewayErrorException(GatewayError.InvalidRequest.invalidClientRequestId)
            }
        }
    }

    private fun mdcEntries(context: RequestContext): Map<String, String> =
        buildMap {
            put(GatewayHeaders.MDC_REQUEST_ID, context.requestId.toString())
            context.clientRequestId?.let { put(GatewayHeaders.MDC_CLIENT_REQUEST_ID, it.value) }
        }
}
