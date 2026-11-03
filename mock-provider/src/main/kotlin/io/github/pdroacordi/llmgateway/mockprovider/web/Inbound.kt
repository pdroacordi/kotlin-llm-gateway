package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.awaitBodyOrNull
import org.springframework.web.server.NotAcceptableStatusException
import org.springframework.web.server.UnsupportedMediaTypeStatusException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.jvm.optionals.getOrNull

/** The journal record the filter attached; every provider route is counted, so it is always there. */
fun ServerRequest.record(): RequestRecord =
    checkNotNull(RequestRecord.of(exchange())) { "no RequestRecord: the route is not counted by ArrivalWebFilter" }

/**
 * Reads the body as a JSON tree (ADR-FND-03-010) and keeps its text for the journal when the request is counted.
 * A Content-Type other than JSON is 415; no Content-Type is accepted, as the providers do.
 */
suspend fun ServerRequest.readJson(mapper: JsonMapper): JsonNode {
    val contentType = headers().contentType().getOrNull()
    if (contentType != null && !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
        throw UnsupportedMediaTypeStatusException(contentType, listOf(MediaType.APPLICATION_JSON))
    }
    val text = awaitBodyOrNull<String>().orEmpty()
    RequestRecord.of(exchange())?.captureBody(text)
    val tree =
        try {
            mapper.readTree(text)
        } catch (_: JacksonException) {
            null
        }
    // Jackson reads an empty body as a missing node.
    if (tree == null || tree.isMissingNode) reject(null, "The request body is not valid JSON.")
    return tree
}

/**
 * Puts the body's `model` on the journal record before the rest of the body is validated, so a 400 is journaled and
 * counted under the model it named (`?model=` filter, per-model counts). Ignored when it is not a string.
 */
fun RequestRecord.noteModel(body: JsonNode) {
    body.member("model")?.takeIf { it.isString }?.let { model = it.stringValue() }
}

/** 406 when an Accept header is present and excludes [type]. */
fun ServerRequest.requireAccepts(type: MediaType) {
    val accepted = headers().accept()
    if (accepted.isNotEmpty() && accepted.none { it.isCompatibleWith(type) }) {
        throw NotAcceptableStatusException(listOf(type))
    }
}
