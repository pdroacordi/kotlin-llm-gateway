package io.github.pdroacordi.llmgateway.mockprovider.provider

enum class ProviderApi {
    OPENAI,
    ANTHROPIC,
    ;

    companion object {
        private const val ANTHROPIC_MESSAGES = "/v1/messages"

        /** The provider whose shape a path's responses take: Anthropic under `/v1/messages`, OpenAI everywhere else. */
        fun forPath(path: String): ProviderApi =
            if (path == ANTHROPIC_MESSAGES || path.startsWith("$ANTHROPIC_MESSAGES/")) ANTHROPIC else OPENAI
    }
}

/** [code] is OpenAI's; Anthropic bodies have none. */
data class ErrorDefaults(
    val type: String,
    val code: String?,
    val message: String,
)

/** Defaults for an injected error that sets only `status` (FND-03 section 4, error bodies and defaults). */
object ProviderErrors {
    private const val INVALID_REQUEST = "invalid_request_error"
    private const val SERVER_ERROR = "server_error"
    private const val API_ERROR = "api_error"
    private const val FIRST_SERVER_STATUS = 500

    private data class Row(
        val openAiType: String,
        val openAiCode: String?,
        val anthropicType: String,
    )

    private val ROWS: Map<Int, Row> =
        mapOf(
            400 to Row(INVALID_REQUEST, null, INVALID_REQUEST),
            401 to Row(INVALID_REQUEST, "invalid_api_key", "authentication_error"),
            402 to Row(INVALID_REQUEST, null, "billing_error"),
            403 to Row(INVALID_REQUEST, null, "permission_error"),
            404 to Row(INVALID_REQUEST, "model_not_found", "not_found_error"),
            409 to Row(INVALID_REQUEST, null, "conflict_error"),
            413 to Row(INVALID_REQUEST, null, "request_too_large"),
            429 to Row("requests", "rate_limit_exceeded", "rate_limit_error"),
            500 to Row(SERVER_ERROR, null, API_ERROR),
            503 to Row("service_unavailable_error", "server_is_overloaded", API_ERROR),
            504 to Row(SERVER_ERROR, null, "timeout_error"),
            // Not an OpenAI status; allowed so tests can use Anthropic's overload status on either route.
            529 to Row(SERVER_ERROR, null, "overloaded_error"),
        )

    fun defaults(
        api: ProviderApi,
        status: Int,
    ): ErrorDefaults {
        val row =
            ROWS[status]
                ?: if (status < FIRST_SERVER_STATUS) {
                    Row(INVALID_REQUEST, null, INVALID_REQUEST)
                } else {
                    Row(SERVER_ERROR, null, API_ERROR)
                }
        val message = "mock-provider injected HTTP $status."
        return when (api) {
            ProviderApi.OPENAI -> ErrorDefaults(row.openAiType, row.openAiCode, message)
            ProviderApi.ANTHROPIC -> ErrorDefaults(row.anthropicType, null, message)
        }
    }
}
