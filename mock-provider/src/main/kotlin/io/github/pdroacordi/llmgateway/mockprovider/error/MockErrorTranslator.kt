package io.github.pdroacordi.llmgateway.mockprovider.error

import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpStatus
import org.springframework.web.ErrorResponse
import org.springframework.web.server.MethodNotAllowedException

// Guards against a cause cycle longer than one link.
private const val MAX_CAUSE_DEPTH = 32

/** The status and fields of an error the mapper writes; the provider `type` comes from the route's API. */
data class MockReply(
    val status: Int,
    val message: String,
    val code: String? = null,
    val param: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Turns anything that escapes a handler or filter into a [MockReply]. Framework exceptions are matched by the status
 * integer they carry (docs/knowledge/gotchas/spring-httpstatus-deprecated-aliases-never-resolved.md); messages are
 * fixed text, never the exception's. [path] has no query string.
 */
fun Throwable.toMockReply(
    method: String,
    path: String,
): MockReply =
    when {
        this is MockRejection -> {
            MockReply(status, reason, code, param)
        }

        causeChain().any { it is DataBufferLimitException } -> {
            MockReply(HttpStatus.CONTENT_TOO_LARGE.value(), "The request body is larger than mock-provider accepts.")
        }

        this is ErrorResponse -> {
            fromStatus(this, method, path)
        }

        else -> {
            MockReply(HttpStatus.INTERNAL_SERVER_ERROR.value(), "mock-provider failed while handling the request.")
        }
    }

private fun Throwable.fromStatus(
    response: ErrorResponse,
    method: String,
    path: String,
): MockReply =
    when (val status = response.statusCode.value()) {
        HttpStatus.BAD_REQUEST.value() -> {
            if (causeChain().any { it is DecodingException }) {
                MockReply(status, "mock-provider could not parse the request body.")
            } else {
                MockReply(status, "mock-provider could not read the request.")
            }
        }

        HttpStatus.NOT_FOUND.value() -> {
            MockReply(status, "Unknown request URL: $method $path.")
        }

        HttpStatus.METHOD_NOT_ALLOWED.value() -> {
            val allowed = (response as? MethodNotAllowedException)?.supportedMethods?.map { it.name() }.orEmpty()
            MockReply(
                status,
                "Method $method is not allowed on $path.",
                headers = mapOf("allow" to allowed.sorted().joinToString(", ")),
            )
        }

        HttpStatus.NOT_ACCEPTABLE.value() -> {
            MockReply(status, "mock-provider cannot produce a response in the requested format.")
        }

        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value() -> {
            MockReply(status, "The request Content-Type is not supported; send application/json.")
        }

        else -> {
            if (response.statusCode.is4xxClientError) {
                MockReply(status, "mock-provider rejected the request with HTTP $status.")
            } else {
                MockReply(HttpStatus.INTERNAL_SERVER_ERROR.value(), "mock-provider failed while handling the request.")
            }
        }
    }

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH)
