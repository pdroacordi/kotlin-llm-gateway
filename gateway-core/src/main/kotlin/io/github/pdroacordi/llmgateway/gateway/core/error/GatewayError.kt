package io.github.pdroacordi.llmgateway.gateway.core.error

import kotlin.time.Duration

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
            val modelRequired =
                InvalidRequest(
                    "'model' is required and must be a non-empty string.",
                    ErrorCodes.INVALID_REQUEST,
                    "model",
                )
            val messagesRequired =
                InvalidRequest(
                    "'messages' is required and must be a non-empty array.",
                    ErrorCodes.INVALID_REQUEST,
                    "messages",
                )
            val streamMistyped = InvalidRequest("'stream' must be a boolean.", ErrorCodes.INVALID_REQUEST, "stream")
            val streamOptionsMistyped =
                InvalidRequest("'stream_options' must be an object.", ErrorCodes.INVALID_REQUEST, "stream_options")
            val includeUsageMistyped =
                InvalidRequest(
                    "'include_usage' must be a boolean.",
                    ErrorCodes.INVALID_REQUEST,
                    "stream_options.include_usage",
                )
            val streamOptionsNeedStream =
                InvalidRequest(
                    "'stream_options' is only allowed when 'stream' is true.",
                    ErrorCodes.INVALID_REQUEST,
                    "stream_options",
                )

            val nameInvalid =
                InvalidRequest(
                    "'name' must be 1 to 64 of a-z, 0-9, '.', '_' or '-', and start with a letter or digit.",
                    ErrorCodes.INVALID_REQUEST,
                    "name",
                )
            val expiresAtInvalid =
                InvalidRequest(
                    "'expires_at' must be null or an RFC 3339 time after now.",
                    ErrorCodes.INVALID_REQUEST,
                    "expires_at",
                )
            val unknownMember =
                InvalidRequest("The body has a member this endpoint does not accept.", ErrorCodes.INVALID_REQUEST)
            val limitInvalid =
                InvalidRequest("'limit' must be an integer from 1 to 100.", ErrorCodes.INVALID_REQUEST, "limit")
            val afterInvalid = InvalidRequest("'after' must be a UUID.", ErrorCodes.INVALID_REQUEST, "after")

            fun messageNotObject(index: Int): InvalidRequest =
                InvalidRequest("Each message must be a JSON object.", ErrorCodes.INVALID_REQUEST, "messages[$index]")

            fun roleRequired(index: Int): InvalidRequest =
                InvalidRequest(
                    "Each message needs a non-empty string 'role'.",
                    ErrorCodes.INVALID_REQUEST,
                    "messages[$index].role",
                )

            fun contentMistyped(index: Int): InvalidRequest =
                InvalidRequest(
                    "'content' must be a string, an array or null.",
                    ErrorCodes.INVALID_REQUEST,
                    "messages[$index].content",
                )
        }
    }

    // Holds no path: a key pasted into a URL would reach the body, and the encoder's DEBUG line (ADR-GW-04-020).
    data object UnknownRoute : GatewayError

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

/** What a provider call can fail with. RTE-02 decides on these; [toReply] renders them (GW-01 section 4). */
sealed interface ProviderFailure : GatewayError {
    data object ModelNotFound : ProviderFailure

    /** [code] and [param] are the provider's, already checked to hold no free text. */
    data class RejectedByProvider(
        val status: Int,
        val code: String?,
        val param: String?,
    ) : ProviderFailure {
        init {
            require(status in HttpStatuses.RELAYED_REJECTIONS) { "A provider rejection is 400, 413 or 422: $status" }
        }
    }

    /** Nothing was sent: this provider cannot honour the request. [param] holds no request content. */
    data class UnsupportedByProvider(
        val code: String,
        val param: String?,
    ) : ProviderFailure

    data object UpstreamAuthFailed : ProviderFailure

    data class UpstreamRateLimited(
        val retryAfter: Duration?,
    ) : ProviderFailure

    data object UpstreamQuotaExhausted : ProviderFailure

    data class ProviderUnavailable(
        val retryAfter: Duration?,
    ) : ProviderFailure

    data object UpstreamTimeout : ProviderFailure

    data object UpstreamError : ProviderFailure
}

/** A bad credential at the edge: one reply for every state a client must not tell apart (ADR-GW-04-004). */
sealed interface Unauthenticated : GatewayError {
    data object MissingKey : Unauthenticated

    data object InvalidKey : Unauthenticated
}

/** What the team store can fail with. [Unavailable] is an outage, never a bad key (ADR-GW-04-005). */
sealed interface TeamStoreFailure : GatewayError {
    data object Unavailable : TeamStoreFailure

    data object TeamNotFound : TeamStoreFailure

    data object KeyNotFound : TeamStoreFailure

    data object TeamNameTaken : TeamStoreFailure

    data object KeyNameTaken : TeamStoreFailure
}
