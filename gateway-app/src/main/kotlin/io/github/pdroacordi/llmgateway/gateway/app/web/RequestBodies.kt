package io.github.pdroacordi.llmgateway.gateway.app.web

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.ObjectNode
import kotlin.jvm.optionals.getOrNull

/**
 * The only body read of a functional route. Spring translates the body limit for annotated controllers only, so it is
 * translated here, where the limit can only be the request's (ADR-GW-01-003).
 */
suspend fun ServerRequest.readJsonObject(trees: ObjectReader): ObjectNode = parseJsonObject(trees, readJsonBody())

/** The checked bytes of a JSON body, for a route that parses them elsewhere; null when there is no body. */
suspend fun ServerRequest.readJsonBody(): ByteArray? {
    requireJsonContentType()
    return try {
        awaitBodyOrNull<ByteArray>()
    } catch (_: DataBufferLimitException) {
        throw GatewayErrorException(GatewayError.ContentTooLarge)
    }
}

fun parseJsonObject(
    trees: ObjectReader,
    bytes: ByteArray?,
): ObjectNode {
    val tree = bytes?.let { trees.readTreeOrNull(it) }
    return tree as? ObjectNode ?: throw GatewayErrorException(GatewayError.InvalidRequest.unreadableBody)
}

// A body with no type is how a no-cors cross-site POST arrives, so absent is refused too (ADR-GW-01-008).
private fun ServerRequest.requireJsonContentType() {
    val contentType =
        try {
            headers().contentType().getOrNull()
        } catch (_: InvalidMediaTypeException) {
            null
        }
    if (contentType == null || !contentType.equalsTypeAndSubtype(MediaType.APPLICATION_JSON)) {
        throw GatewayErrorException(GatewayError.UnsupportedMediaType)
    }
}
