package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.error.MockRejection
import io.github.pdroacordi.llmgateway.mockprovider.journal.PresentedCredential
import io.github.pdroacordi.llmgateway.mockprovider.journal.Scheme
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import org.springframework.http.HttpHeaders

/**
 * Any non-empty credential is accepted and never compared with anything (FND-03 section 4, Credentials): OpenAI
 * routes take `Authorization: Bearer`, `/v1/messages` also `x-api-key`. A missing one is 401 in the route's shape.
 */
class Credentials(
    private val required: Boolean,
) {
    fun check(
        api: ProviderApi,
        headers: HttpHeaders,
    ) {
        if (!required) return
        val presented =
            PresentedCredential.of(
                headers.getFirst(HttpHeaders.AUTHORIZATION),
                headers.getFirst(ArrivalWebFilter.X_API_KEY),
            )
        val accepted =
            when (api) {
                ProviderApi.OPENAI -> presented?.scheme == Scheme.BEARER
                ProviderApi.ANTHROPIC -> presented != null
            }
        if (!accepted) throw MockRejection(UNAUTHORIZED, MISSING, code = "invalid_api_key")
    }

    private companion object {
        const val UNAUTHORIZED = 401
        const val MISSING = "No API key was provided to mock-provider."
    }
}
