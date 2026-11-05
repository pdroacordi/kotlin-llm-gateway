package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure

/**
 * Selects the adapter by deployment. The resolver yields only enabled providers, so a miss means that provider has no
 * embeddings, and core names none (ADR-GW-05-003). An empty map is valid.
 */
class RoutingEmbeddingModelPort(
    private val ports: Map<ProviderId, EmbeddingModelPort>,
) : EmbeddingModelPort {
    override suspend fun embed(call: EmbeddingCall): EmbeddingResult =
        ports[call.deployment.provider]?.embed(call) ?: EmbeddingResult.Failed(UNSUPPORTED)

    private companion object {
        val UNSUPPORTED = ProviderFailure.UnsupportedByProvider("unsupported_value", "model")
    }
}
