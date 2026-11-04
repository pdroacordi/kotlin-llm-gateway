package io.github.pdroacordi.llmgateway.gateway.core.error

import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// One case per row of FND-04 section 4, "Implemented by FND-04", and of GW-01 section 4.
class ErrorReplyTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `each implemented variant maps to its contract row`(
        error: GatewayError,
        expected: ErrorReply,
    ) {
        assertEquals(expected, error.toReply(REQUEST_ID))
    }

    @Test
    fun `the allow header lists the allowed methods sorted and comma-separated`() {
        val reply = GatewayError.MethodNotAllowed("PUT", setOf("POST", "GET", "DELETE")).toReply(REQUEST_ID)

        assertEquals(mapOf("allow" to "DELETE, GET, POST"), reply.headers)
    }

    @Test
    fun `a 5xx message ends with the request ID`() {
        val reply = GatewayError.Internal.toReply(REQUEST_ID)

        assertTrue(reply.message.contains(REQUEST_ID.toString())) { reply.message }
    }

    @Test
    fun `the unknown-URL message keeps only the first 200 characters of the path`() {
        val path = "/" + "a".repeat(300)

        val reply = GatewayError.UnknownRoute("GET", path).toReply(REQUEST_ID)

        assertEquals("Unknown request URL: GET ${path.take(200)}.", reply.message)
    }

    @ParameterizedTest
    @ValueSource(ints = [399, 500, 200, 0])
    fun `a rejected request must carry a 4xx status`(status: Int) {
        assertThrows<IllegalArgumentException> { GatewayError.RejectedRequest(status) }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 409, 499])
    fun `a rejected request accepts every 4xx status`(status: Int) {
        assertEquals(status, GatewayError.RejectedRequest(status).toReply(REQUEST_ID).status)
    }

    @Test
    fun `retry-after rounds up to whole seconds and retry-after-ms keeps the milliseconds`() {
        val reply = ProviderFailure.UpstreamRateLimited(1500.milliseconds).toReply(REQUEST_ID)

        assertEquals(mapOf("retry-after" to "2", "retry-after-ms" to "1500"), reply.headers)
    }

    @Test
    fun `a whole number of seconds is not rounded up`() {
        val reply = ProviderFailure.ProviderUnavailable(7.seconds).toReply(REQUEST_ID)

        assertEquals(mapOf("retry-after" to "7", "retry-after-ms" to "7000"), reply.headers)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failuresWithoutRetryAfter")
    fun `no retry header is sent when the provider gave no wait`(failure: ProviderFailure) {
        val headers = failure.toReply(REQUEST_ID).headers

        assertTrue(headers.keys.none { it.startsWith("retry-after") }) { headers.toString() }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serverSideFailures")
    fun `a provider failure answered with a 5xx names the request ID at the end of its message`(
        failure: ProviderFailure,
    ) {
        val reply = failure.toReply(REQUEST_ID)

        assertTrue(reply.status >= 500) { reply.toString() }
        assertTrue(reply.message.endsWith("Include the request ID $REQUEST_ID if you report it.")) { reply.message }
    }

    @ParameterizedTest
    @ValueSource(ints = [399, 401, 404, 429, 500])
    fun `RejectedByProvider accepts only 400, 413 and 422`(status: Int) {
        assertThrows<IllegalArgumentException> { ProviderFailure.RejectedByProvider(status, null, null) }
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 413, 422])
    fun `RejectedByProvider keeps the provider's status`(status: Int) {
        val reply = ProviderFailure.RejectedByProvider(status, null, null).toReply(REQUEST_ID)

        assertEquals(status, reply.status)
        assertEquals("upstream_rejected_request", reply.code)
    }

    companion object {
        private val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        private const val INVALID = "invalid_request_error"
        private const val SERVER = "server_error"
        private const val REJECTED = "The provider rejected the request; see the code and param members."
        private const val REPORT = "Include the request ID 01890a5d-ac96-774b-bcce-b302099a8057 if you report it."

        @JvmStatic
        fun rows(): List<Arguments> = ROWS

        @JvmStatic
        fun failuresWithoutRetryAfter(): List<ProviderFailure> =
            listOf(ProviderFailure.UpstreamRateLimited(null), ProviderFailure.ProviderUnavailable(null))

        @JvmStatic
        fun serverSideFailures(): List<ProviderFailure> =
            listOf(
                ProviderFailure.UpstreamAuthFailed,
                ProviderFailure.UpstreamQuotaExhausted,
                ProviderFailure.ProviderUnavailable(null),
                ProviderFailure.UpstreamTimeout,
                ProviderFailure.UpstreamError,
            )

        private val ROWS: List<Arguments> =
            listOf(
                Arguments.of(
                    named(
                        "raised InvalidRequest",
                        GatewayError.InvalidRequest("Probe message.", "probe_code", "probe_param"),
                    ),
                    ErrorReply(400, "Probe message.", INVALID, "probe_param", "probe_code"),
                ),
                Arguments.of(
                    named("unreadable body", GatewayError.InvalidRequest.unreadableBody),
                    ErrorReply(
                        400,
                        "The request body could not be read as JSON of the expected shape.",
                        INVALID,
                        null,
                        "invalid_body",
                    ),
                ),
                Arguments.of(
                    named("missing or mistyped value", GatewayError.InvalidRequest.missingOrMistypedValue),
                    ErrorReply(
                        400,
                        "The request is missing a required value or has a value of the wrong type.",
                        INVALID,
                        null,
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("bad x-client-request-id", GatewayError.InvalidRequest.invalidClientRequestId),
                    ErrorReply(
                        400,
                        "The x-client-request-id header must be one value of 1 to 512 printable ASCII characters.",
                        INVALID,
                        null,
                        "invalid_client_request_id",
                    ),
                ),
                Arguments.of(
                    named("unknown route", GatewayError.UnknownRoute("GET", "/v1/does-not-exist")),
                    ErrorReply(404, "Unknown request URL: GET /v1/does-not-exist.", INVALID, null, "unknown_url"),
                ),
                Arguments.of(
                    named("method not allowed", GatewayError.MethodNotAllowed("GET", setOf("POST"))),
                    ErrorReply(
                        405,
                        "Method GET is not allowed for this URL.",
                        INVALID,
                        null,
                        "method_not_allowed",
                        mapOf("allow" to "POST"),
                    ),
                ),
                Arguments.of(
                    named("not acceptable", GatewayError.NotAcceptable),
                    ErrorReply(
                        406,
                        "None of the media types in the Accept header can be produced by this endpoint.",
                        INVALID,
                        null,
                        "not_acceptable",
                    ),
                ),
                Arguments.of(
                    named("content too large", GatewayError.ContentTooLarge),
                    ErrorReply(
                        413,
                        "The request body is larger than this endpoint accepts.",
                        INVALID,
                        null,
                        "request_too_large",
                    ),
                ),
                Arguments.of(
                    named("unsupported media type", GatewayError.UnsupportedMediaType),
                    ErrorReply(
                        415,
                        "The request Content-Type is not supported by this endpoint.",
                        INVALID,
                        null,
                        "unsupported_media_type",
                    ),
                ),
                Arguments.of(
                    named("other framework 4xx", GatewayError.RejectedRequest(409)),
                    ErrorReply(409, "The request was rejected with HTTP status 409.", INVALID, null, null),
                ),
                Arguments.of(
                    named("internal", GatewayError.Internal),
                    ErrorReply(
                        500,
                        "The gateway had an internal error. Include the request ID " +
                            "01890a5d-ac96-774b-bcce-b302099a8057 if you report it.",
                        "server_error",
                        null,
                        "internal_error",
                    ),
                ),
                Arguments.of(
                    named("model not found", ProviderFailure.ModelNotFound),
                    ErrorReply(
                        404,
                        "The requested model does not exist or is not available.",
                        INVALID,
                        "model",
                        "model_not_found",
                    ),
                ),
                Arguments.of(
                    named(
                        "rejected by the provider",
                        ProviderFailure.RejectedByProvider(400, "context_length_exceeded", "messages[0].content"),
                    ),
                    ErrorReply(400, REJECTED, INVALID, "messages[0].content", "context_length_exceeded"),
                ),
                Arguments.of(
                    named("rejected without a usable code", ProviderFailure.RejectedByProvider(422, null, null)),
                    ErrorReply(422, REJECTED, INVALID, null, "upstream_rejected_request"),
                ),
                Arguments.of(
                    named(
                        "a member the provider cannot honour",
                        ProviderFailure.UnsupportedByProvider("unsupported_parameter", "seed"),
                    ),
                    ErrorReply(
                        400,
                        "The selected model's provider does not support this request; see the code and param members.",
                        INVALID,
                        "seed",
                        "unsupported_parameter",
                    ),
                ),
                Arguments.of(
                    named("upstream auth failed", ProviderFailure.UpstreamAuthFailed),
                    ErrorReply(
                        502,
                        "The gateway could not authenticate with the provider. $REPORT",
                        SERVER,
                        null,
                        "upstream_auth_failed",
                        mapOf("x-should-retry" to "false"),
                    ),
                ),
                Arguments.of(
                    named("upstream quota exhausted", ProviderFailure.UpstreamQuotaExhausted),
                    ErrorReply(
                        503,
                        "The gateway's provider account has no quota left. $REPORT",
                        SERVER,
                        null,
                        "upstream_quota_exhausted",
                        mapOf("x-should-retry" to "false"),
                    ),
                ),
                Arguments.of(
                    named("upstream rate limited", ProviderFailure.UpstreamRateLimited(7.seconds)),
                    ErrorReply(
                        429,
                        "The provider is rate limiting the gateway; retry later.",
                        "rate_limit_error",
                        null,
                        "upstream_rate_limited",
                        mapOf("retry-after" to "7", "retry-after-ms" to "7000"),
                    ),
                ),
                Arguments.of(
                    named("provider unavailable", ProviderFailure.ProviderUnavailable(null)),
                    ErrorReply(
                        503,
                        "The provider cannot be reached or is overloaded. $REPORT",
                        SERVER,
                        null,
                        "provider_unavailable",
                    ),
                ),
                Arguments.of(
                    named("upstream timeout", ProviderFailure.UpstreamTimeout),
                    ErrorReply(504, "The provider did not answer in time. $REPORT", SERVER, null, "upstream_timeout"),
                ),
                Arguments.of(
                    named("upstream error", ProviderFailure.UpstreamError),
                    ErrorReply(
                        502,
                        "The provider returned an error or an unreadable response. $REPORT",
                        SERVER,
                        null,
                        "upstream_error",
                    ),
                ),
                Arguments.of(
                    named("model missing", GatewayError.InvalidRequest.modelRequired),
                    ErrorReply(
                        400,
                        "'model' is required and must be a non-empty string.",
                        INVALID,
                        "model",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("messages missing", GatewayError.InvalidRequest.messagesRequired),
                    ErrorReply(
                        400,
                        "'messages' is required and must be a non-empty array.",
                        INVALID,
                        "messages",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("message not an object", GatewayError.InvalidRequest.messageNotObject(2)),
                    ErrorReply(400, "Each message must be a JSON object.", INVALID, "messages[2]", "invalid_request"),
                ),
                Arguments.of(
                    named("role missing", GatewayError.InvalidRequest.roleRequired(0)),
                    ErrorReply(
                        400,
                        "Each message needs a non-empty string 'role'.",
                        INVALID,
                        "messages[0].role",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("content mistyped", GatewayError.InvalidRequest.contentMistyped(1)),
                    ErrorReply(
                        400,
                        "'content' must be a string, an array or null.",
                        INVALID,
                        "messages[1].content",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("stream mistyped", GatewayError.InvalidRequest.streamMistyped),
                    ErrorReply(400, "'stream' must be a boolean.", INVALID, "stream", "invalid_request"),
                ),
                Arguments.of(
                    named("stream_options mistyped", GatewayError.InvalidRequest.streamOptionsMistyped),
                    ErrorReply(
                        400,
                        "'stream_options' must be an object.",
                        INVALID,
                        "stream_options",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("include_usage mistyped", GatewayError.InvalidRequest.includeUsageMistyped),
                    ErrorReply(
                        400,
                        "'include_usage' must be a boolean.",
                        INVALID,
                        "stream_options.include_usage",
                        "invalid_request",
                    ),
                ),
                Arguments.of(
                    named("stream_options without stream", GatewayError.InvalidRequest.streamOptionsNeedStream),
                    ErrorReply(
                        400,
                        "'stream_options' is only allowed when 'stream' is true.",
                        INVALID,
                        "stream_options",
                        "invalid_request",
                    ),
                ),
            )
    }
}
