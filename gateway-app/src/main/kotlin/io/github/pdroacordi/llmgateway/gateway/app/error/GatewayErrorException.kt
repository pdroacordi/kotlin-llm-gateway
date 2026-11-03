package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError

/**
 * How a handler or filter raises a [GatewayError] at the HTTP edge. No stack trace: an expected failure is not a bug,
 * and the class name is the only message, so nothing from the request can leak through it.
 */
class GatewayErrorException(
    val error: GatewayError,
    cause: Throwable? = null,
) : RuntimeException(error::class.simpleName, cause, false, false)
