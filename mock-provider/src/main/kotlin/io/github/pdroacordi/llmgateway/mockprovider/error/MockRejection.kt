package io.github.pdroacordi.llmgateway.mockprovider.error

/**
 * A request the mock refuses (validation, credentials, admin input): the mapper writes it in the route's provider
 * shape. [reason] is fixed text written for the mock, never request content. No stack trace: it is not a bug.
 */
class MockRejection(
    val status: Int,
    val reason: String,
    val param: String? = null,
    val code: String? = null,
) : RuntimeException(reason, null, false, false)

/** Throws a 400 `invalid_request_error` naming [param]. */
fun reject(
    param: String?,
    reason: String,
    code: String? = null,
): Nothing = throw MockRejection(BAD_REQUEST, reason, param, code)

private const val BAD_REQUEST = 400
