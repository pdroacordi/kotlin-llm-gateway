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
import kotlin.uuid.Uuid

// One case per row of FND-04 section 4, "Implemented by FND-04".
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

    companion object {
        private val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        private const val INVALID = "invalid_request_error"

        @JvmStatic
        fun rows(): List<Arguments> = ROWS

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
            )
    }
}
