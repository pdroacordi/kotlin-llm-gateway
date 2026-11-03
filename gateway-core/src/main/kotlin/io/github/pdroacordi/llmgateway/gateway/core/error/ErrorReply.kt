package io.github.pdroacordi.llmgateway.gateway.core.error

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId

/** Status and OpenAI error fields for one [GatewayError]; [headers] names are lowercase. */
data class ErrorReply(
    val status: Int,
    val message: String,
    val type: String,
    val param: String?,
    val code: String?,
    val headers: Map<String, String> = emptyMap(),
)

/** `type` values: OpenAI's value for the equivalent condition (ADR-FND-04-004). */
object ErrorTypes {
    const val INVALID_REQUEST = "invalid_request_error"
    const val SERVER_ERROR = "server_error"
}

/** Stable discriminators; a shipped (status, type, code) row never changes (ADR-015). */
object ErrorCodes {
    const val INVALID_BODY = "invalid_body"
    const val INVALID_REQUEST = "invalid_request"
    const val INVALID_CLIENT_REQUEST_ID = "invalid_client_request_id"
    const val UNKNOWN_URL = "unknown_url"
    const val METHOD_NOT_ALLOWED = "method_not_allowed"
    const val NOT_ACCEPTABLE = "not_acceptable"
    const val REQUEST_TOO_LARGE = "request_too_large"
    const val UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    const val INTERNAL_ERROR = "internal_error"
}

internal object HttpStatuses {
    const val BAD_REQUEST = 400
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val NOT_ACCEPTABLE = 406
    const val CONTENT_TOO_LARGE = 413
    const val UNSUPPORTED_MEDIA_TYPE = 415
    const val INTERNAL_SERVER_ERROR = 500
    val CLIENT_ERRORS = 400..499
}

private const val MAX_PATH_IN_MESSAGE = 200

/** The FND-04 section 4 table. Exhaustive, so a new variant does not compile without its row. */
fun GatewayError.toReply(requestId: RequestId): ErrorReply =
    when (this) {
        is GatewayError.InvalidRequest -> {
            clientError(HttpStatuses.BAD_REQUEST, message, code, param)
        }

        is GatewayError.UnknownRoute -> {
            clientError(
                HttpStatuses.NOT_FOUND,
                "Unknown request URL: $method ${path.take(MAX_PATH_IN_MESSAGE)}.",
                ErrorCodes.UNKNOWN_URL,
            )
        }

        is GatewayError.MethodNotAllowed -> {
            clientError(
                HttpStatuses.METHOD_NOT_ALLOWED,
                "Method $method is not allowed for this URL.",
                ErrorCodes.METHOD_NOT_ALLOWED,
                headers = mapOf("allow" to allowed.sorted().joinToString(", ")),
            )
        }

        GatewayError.NotAcceptable -> {
            clientError(
                HttpStatuses.NOT_ACCEPTABLE,
                "None of the media types in the Accept header can be produced by this endpoint.",
                ErrorCodes.NOT_ACCEPTABLE,
            )
        }

        GatewayError.ContentTooLarge -> {
            clientError(
                HttpStatuses.CONTENT_TOO_LARGE,
                "The request body is larger than this endpoint accepts.",
                ErrorCodes.REQUEST_TOO_LARGE,
            )
        }

        GatewayError.UnsupportedMediaType -> {
            clientError(
                HttpStatuses.UNSUPPORTED_MEDIA_TYPE,
                "The request Content-Type is not supported by this endpoint.",
                ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
            )
        }

        is GatewayError.RejectedRequest -> {
            clientError(status, "The request was rejected with HTTP status $status.", code = null)
        }

        GatewayError.Internal -> {
            ErrorReply(
                status = HttpStatuses.INTERNAL_SERVER_ERROR,
                message = "The gateway had an internal error. Include the request ID $requestId if you report it.",
                type = ErrorTypes.SERVER_ERROR,
                param = null,
                code = ErrorCodes.INTERNAL_ERROR,
            )
        }
    }

private fun clientError(
    status: Int,
    message: String,
    code: String?,
    param: String? = null,
    headers: Map<String, String> = emptyMap(),
): ErrorReply = ErrorReply(status, message, ErrorTypes.INVALID_REQUEST, param, code, headers)
