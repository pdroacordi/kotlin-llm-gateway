package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.anthropic.AnthropicRenderer
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.InjectedError
import io.github.pdroacordi.llmgateway.mockprovider.openai.OpenAiRenderer
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderErrors

/** Error bodies in either provider's shape, for injected errors (`Serving`) and the mapper. */
class ErrorBodies(
    private val openAi: OpenAiRenderer,
    private val anthropic: AnthropicRenderer,
) {
    fun render(
        api: ProviderApi,
        body: ErrorBody,
        requestId: String,
    ): String =
        when (api) {
            ProviderApi.OPENAI -> openAi.error(body)
            ProviderApi.ANTHROPIC -> anthropic.error(body, requestId)
        }

    companion object {
        /** A scenario error with the section 4 defaults filled in. */
        fun of(
            api: ProviderApi,
            error: InjectedError,
        ): ErrorBody {
            val defaults = ProviderErrors.defaults(api, error.status)
            return ErrorBody(
                status = error.status,
                type = error.type ?: defaults.type,
                code = error.code ?: defaults.code,
                message = error.message ?: defaults.message,
                param = error.param,
            )
        }

        /** An error the mock raises itself: [code] and [param] as given, the type from the defaults table. */
        fun of(
            api: ProviderApi,
            status: Int,
            message: String,
            code: String?,
            param: String?,
        ): ErrorBody =
            ErrorBody(
                status = status,
                type = ProviderErrors.defaults(api, status).type,
                code = if (api == ProviderApi.OPENAI) code else null,
                message = message,
                param = param,
            )
    }
}
