package io.github.pdroacordi.llmgateway.gateway.core.error

/**
 * Expected failures, each mapped to one OpenAI error row by [toReply]. Later items add their variant and row
 * (FND-04 section 4, "Reserved for later items").
 */
sealed interface GatewayError {
    data class InvalidRequest(
        /** Client-safe English text; never contains request content or an exception message. */
        val message: String,
        val code: String,
        val param: String? = null,
    ) : GatewayError {
        companion object {
            val unreadableBody =
                InvalidRequest(
                    "The request body could not be read as JSON of the expected shape.",
                    ErrorCodes.INVALID_BODY,
                )
            val missingOrMistypedValue =
                InvalidRequest(
                    "The request is missing a required value or has a value of the wrong type.",
                    ErrorCodes.INVALID_REQUEST,
                )
            val invalidClientRequestId =
                InvalidRequest(
                    "The x-client-request-id header must be one value of 1 to 512 printable ASCII characters.",
                    ErrorCodes.INVALID_CLIENT_REQUEST_ID,
                )
        }
    }

    /** [path] is the request path without its query string. */
    data class UnknownRoute(
        val method: String,
        val path: String,
    ) : GatewayError

    data class MethodNotAllowed(
        val method: String,
        val allowed: Set<String>,
    ) : GatewayError

    data object NotAcceptable : GatewayError

    data object ContentTooLarge : GatewayError

    data object UnsupportedMediaType : GatewayError

    /** A framework 4xx with no row of its own. */
    data class RejectedRequest(
        val status: Int,
    ) : GatewayError {
        init {
            require(status in HttpStatuses.CLIENT_ERRORS) { "RejectedRequest needs a 4xx status, got $status" }
        }
    }

    /** Bugs, non-gateway 5xx and anything untranslated. */
    data object Internal : GatewayError
}
