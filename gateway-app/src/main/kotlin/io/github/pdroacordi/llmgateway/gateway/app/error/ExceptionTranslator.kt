package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponse
import org.springframework.web.server.MethodNotAllowedException
import org.springframework.web.server.ServerWebInputException

// Guards against a cause cycle longer than one link, which Throwable.cause does not prevent.
private const val MAX_CAUSE_DEPTH = 32

/**
 * Turns anything that escapes a request into a [GatewayError] (FND-04 section 4). Framework exceptions are matched by
 * the status they carry, not their class, so renames such as Spring 7's `ContentTooLargeException` do not matter.
 */
fun Throwable.toGatewayError(method: String): GatewayError =
    when {
        this is GatewayErrorException -> {
            error
        }

        // Only the request-body read path means the client sent too much; Spring wraps it as ServerWebInputException
        // or ContentTooLargeException (413, below). A limit hit elsewhere, e.g. on an upstream response, is a 500.
        this is ServerWebInputException && causeChain().any { it is DataBufferLimitException } -> {
            GatewayError.ContentTooLarge
        }

        this is ErrorResponse -> {
            fromStatus(this, method)
        }

        else -> {
            GatewayError.Internal
        }
    }

// Matched on integers: a branch on the deprecated PAYLOAD_TOO_LARGE never matches, because resolve(413) returns
// CONTENT_TOO_LARGE (docs/knowledge/gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md).
private fun Throwable.fromStatus(
    response: ErrorResponse,
    method: String,
): GatewayError =
    when (val status = response.statusCode.value()) {
        HttpStatus.BAD_REQUEST.value() -> {
            if (causeChain().any { it is DecodingException }) {
                GatewayError.InvalidRequest.unreadableBody
            } else {
                GatewayError.InvalidRequest.missingOrMistypedValue
            }
        }

        HttpStatus.NOT_FOUND.value() -> {
            GatewayError.UnknownRoute
        }

        HttpStatus.METHOD_NOT_ALLOWED.value() -> {
            GatewayError.MethodNotAllowed(method, allowedMethods(response))
        }

        HttpStatus.NOT_ACCEPTABLE.value() -> {
            GatewayError.NotAcceptable
        }

        HttpStatus.CONTENT_TOO_LARGE.value() -> {
            GatewayError.ContentTooLarge
        }

        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value() -> {
            GatewayError.UnsupportedMediaType
        }

        else -> {
            if (response.statusCode.is4xxClientError) GatewayError.RejectedRequest(status) else GatewayError.Internal
        }
    }

private fun allowedMethods(response: ErrorResponse): Set<String> =
    when (response) {
        is MethodNotAllowedException -> {
            response.supportedMethods.map { it.name() }.toSet()
        }

        else -> {
            response.headers.allow
                .map { it.name() }
                .toSet()
        }
    }

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)
