package io.github.pdroacordi.llmgateway.gateway.core.error

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

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
    const val RATE_LIMIT = "rate_limit_error"
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
    const val UNSUPPORTED_VALUE = "unsupported_value"
    const val UNSUPPORTED_PARAMETER = "unsupported_parameter"
    const val MODEL_NOT_FOUND = "model_not_found"
    const val UPSTREAM_REJECTED_REQUEST = "upstream_rejected_request"
    const val UPSTREAM_AUTH_FAILED = "upstream_auth_failed"
    const val UPSTREAM_RATE_LIMITED = "upstream_rate_limited"
    const val UPSTREAM_QUOTA_EXHAUSTED = "upstream_quota_exhausted"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val UPSTREAM_TIMEOUT = "upstream_timeout"
    const val UPSTREAM_ERROR = "upstream_error"
    const val MISSING_API_KEY = "missing_api_key"
    const val INVALID_API_KEY = "invalid_api_key"
    const val TEAM_STORE_UNAVAILABLE = "team_store_unavailable"
    const val TEAM_NOT_FOUND = "team_not_found"
    const val KEY_NOT_FOUND = "key_not_found"
    const val TEAM_NAME_TAKEN = "team_name_taken"
    const val KEY_NAME_TAKEN = "key_name_taken"
}

internal object HttpStatuses {
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val NOT_FOUND = 404
    const val METHOD_NOT_ALLOWED = 405
    const val NOT_ACCEPTABLE = 406
    const val CONFLICT = 409
    const val CONTENT_TOO_LARGE = 413
    const val UNSUPPORTED_MEDIA_TYPE = 415
    const val UNPROCESSABLE_CONTENT = 422
    const val TOO_MANY_REQUESTS = 429
    const val INTERNAL_SERVER_ERROR = 500
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504
    val CLIENT_ERRORS = 400..499
    val RELAYED_REJECTIONS = setOf(BAD_REQUEST, CONTENT_TOO_LARGE, UNPROCESSABLE_CONTENT)
}

private const val WWW_AUTHENTICATE = "www-authenticate"

// x-should-retry: openai-python obeys it over its status rules, and a retry cannot help these two (ADR-GW-01-005).
private val NO_RETRY = mapOf("x-should-retry" to "false")

/** A 5xx row of GW-01 section 4; the message gains the request ID, as ADR-015 requires. */
private class ServerFailureRow(
    private val status: Int,
    private val message: String,
    private val code: String,
) {
    fun reply(
        requestId: RequestId,
        headers: Map<String, String> = emptyMap(),
    ): ErrorReply =
        ErrorReply(
            status = status,
            message = "$message Include the request ID $requestId if you report it.",
            type = ErrorTypes.SERVER_ERROR,
            param = null,
            code = code,
            headers = headers,
        )
}

private val AUTH_FAILED =
    ServerFailureRow(
        HttpStatuses.BAD_GATEWAY,
        "The gateway could not authenticate with the provider.",
        ErrorCodes.UPSTREAM_AUTH_FAILED,
    )
private val QUOTA_EXHAUSTED =
    ServerFailureRow(
        HttpStatuses.SERVICE_UNAVAILABLE,
        "The gateway's provider account has no quota left.",
        ErrorCodes.UPSTREAM_QUOTA_EXHAUSTED,
    )
private val UNAVAILABLE =
    ServerFailureRow(
        HttpStatuses.SERVICE_UNAVAILABLE,
        "The provider cannot be reached or is overloaded.",
        ErrorCodes.PROVIDER_UNAVAILABLE,
    )
private val TIMED_OUT =
    ServerFailureRow(
        HttpStatuses.GATEWAY_TIMEOUT,
        "The provider did not answer in time.",
        ErrorCodes.UPSTREAM_TIMEOUT,
    )
private val STORE_UNAVAILABLE =
    ServerFailureRow(
        HttpStatuses.SERVICE_UNAVAILABLE,
        "The gateway cannot reach its team store.",
        ErrorCodes.TEAM_STORE_UNAVAILABLE,
    )
private val UNREADABLE =
    ServerFailureRow(
        HttpStatuses.BAD_GATEWAY,
        "The provider returned an error or an unreadable response.",
        ErrorCodes.UPSTREAM_ERROR,
    )

/** The FND-04, GW-01 and GW-04 section 4 tables. Exhaustive, so a new variant does not compile without its row. */
fun GatewayError.toReply(requestId: RequestId): ErrorReply =
    when (this) {
        is GatewayError.InvalidRequest -> {
            clientError(HttpStatuses.BAD_REQUEST, message, code, param)
        }

        GatewayError.UnknownRoute -> {
            clientError(HttpStatuses.NOT_FOUND, "Unknown request URL.", ErrorCodes.UNKNOWN_URL)
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
            internalReply(requestId)
        }

        is Unauthenticated -> {
            unauthenticatedReply(this)
        }

        is TeamStoreFailure -> {
            teamStoreReply(this, requestId)
        }

        is ProviderFailure -> {
            providerReply(this, requestId)
        }
    }

private fun providerReply(
    failure: ProviderFailure,
    requestId: RequestId,
): ErrorReply =
    when (failure) {
        ProviderFailure.ModelNotFound -> {
            clientError(
                HttpStatuses.NOT_FOUND,
                "The requested model does not exist or is not available.",
                ErrorCodes.MODEL_NOT_FOUND,
                param = "model",
            )
        }

        is ProviderFailure.RejectedByProvider -> {
            clientError(
                failure.status,
                "The provider rejected the request; see the code and param members.",
                failure.code ?: ErrorCodes.UPSTREAM_REJECTED_REQUEST,
                failure.param,
            )
        }

        is ProviderFailure.UnsupportedByProvider -> {
            clientError(
                HttpStatuses.BAD_REQUEST,
                "The selected model's provider does not support this request; see the code and param members.",
                failure.code,
                failure.param,
            )
        }

        ProviderFailure.UpstreamAuthFailed -> {
            AUTH_FAILED.reply(requestId, NO_RETRY)
        }

        ProviderFailure.UpstreamQuotaExhausted -> {
            QUOTA_EXHAUSTED.reply(requestId, NO_RETRY)
        }

        is ProviderFailure.UpstreamRateLimited -> {
            ErrorReply(
                status = HttpStatuses.TOO_MANY_REQUESTS,
                message = "The provider is rate limiting the gateway; retry later.",
                type = ErrorTypes.RATE_LIMIT,
                param = null,
                code = ErrorCodes.UPSTREAM_RATE_LIMITED,
                headers = retryHeaders(failure.retryAfter),
            )
        }

        is ProviderFailure.ProviderUnavailable -> {
            UNAVAILABLE.reply(requestId, retryHeaders(failure.retryAfter))
        }

        ProviderFailure.UpstreamTimeout -> {
            TIMED_OUT.reply(requestId)
        }

        ProviderFailure.UpstreamError -> {
            UNREADABLE.reply(requestId)
        }
    }

private fun unauthenticatedReply(failure: Unauthenticated): ErrorReply =
    when (failure) {
        Unauthenticated.MissingKey -> {
            clientError(
                HttpStatuses.UNAUTHORIZED,
                "Missing API key. Send it as Authorization: Bearer <key>.",
                ErrorCodes.MISSING_API_KEY,
                headers = mapOf(WWW_AUTHENTICATE to "Bearer"),
            )
        }

        Unauthenticated.InvalidKey -> {
            clientError(
                HttpStatuses.UNAUTHORIZED,
                "The API key is invalid.",
                ErrorCodes.INVALID_API_KEY,
                headers = mapOf(WWW_AUTHENTICATE to "Bearer error=\"invalid_token\""),
            )
        }
    }

private fun teamStoreReply(
    failure: TeamStoreFailure,
    requestId: RequestId,
): ErrorReply =
    when (failure) {
        TeamStoreFailure.Unavailable -> {
            STORE_UNAVAILABLE.reply(requestId)
        }

        TeamStoreFailure.TeamNotFound -> {
            clientError(HttpStatuses.NOT_FOUND, "No such team.", ErrorCodes.TEAM_NOT_FOUND)
        }

        TeamStoreFailure.KeyNotFound -> {
            clientError(HttpStatuses.NOT_FOUND, "No such key.", ErrorCodes.KEY_NOT_FOUND)
        }

        TeamStoreFailure.TeamNameTaken -> {
            clientError(HttpStatuses.CONFLICT, "A team with this name exists.", ErrorCodes.TEAM_NAME_TAKEN, "name")
        }

        TeamStoreFailure.KeyNameTaken -> {
            clientError(
                HttpStatuses.CONFLICT,
                "This team has an active key with this name.",
                ErrorCodes.KEY_NAME_TAKEN,
                "name",
            )
        }
    }

private fun retryHeaders(retryAfter: Duration?): Map<String, String> {
    if (retryAfter == null) return emptyMap()
    return mapOf(
        "retry-after" to retryAfter.roundedUp(DurationUnit.SECONDS).toString(),
        "retry-after-ms" to retryAfter.roundedUp(DurationUnit.MILLISECONDS).toString(),
    )
}

private fun Duration.roundedUp(unit: DurationUnit): Long {
    val whole = toLong(unit)
    return if (whole.toDuration(unit) < this) whole + 1 else whole
}

private fun clientError(
    status: Int,
    message: String,
    code: String?,
    param: String? = null,
    headers: Map<String, String> = emptyMap(),
): ErrorReply = ErrorReply(status, message, ErrorTypes.INVALID_REQUEST, param, code, headers)

private fun internalReply(requestId: RequestId): ErrorReply =
    ErrorReply(
        status = HttpStatuses.INTERNAL_SERVER_ERROR,
        message = "The gateway had an internal error. Include the request ID $requestId if you report it.",
        type = ErrorTypes.SERVER_ERROR,
        param = null,
        code = ErrorCodes.INTERNAL_ERROR,
    )
