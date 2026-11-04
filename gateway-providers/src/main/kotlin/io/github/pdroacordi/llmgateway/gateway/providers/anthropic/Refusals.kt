package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorCodes
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields

/** Collects the first refusal of one write; what is written after it is thrown away, so writing simply goes on. */
internal class Refusals {
    var first: ProviderFailure.UnsupportedByProvider? = null
        private set

    fun unsupportedParameter(param: String) = refuse(ErrorCodes.UNSUPPORTED_PARAMETER, param)

    fun unsupportedValue(param: String) = refuse(ErrorCodes.UNSUPPORTED_VALUE, param)

    fun invalidRequest(param: String) = refuse(ErrorCodes.INVALID_REQUEST, param)

    // A path can hold a member's name, which comes from the client.
    private fun refuse(
        code: String,
        param: String,
    ) {
        if (first == null) first = ProviderFailure.UnsupportedByProvider(code, ProviderErrorFields.safeParam(param))
    }
}
