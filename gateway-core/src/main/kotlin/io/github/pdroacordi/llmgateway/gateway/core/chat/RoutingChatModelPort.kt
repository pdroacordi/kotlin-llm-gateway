package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Selects the adapter by deployment; a provider with no adapter is a model that is not available. */
class RoutingChatModelPort(
    private val ports: Map<ProviderId, ChatModelPort>,
) : ChatModelPort {
    override suspend fun complete(call: ChatCall): ProviderResult =
        ports[call.deployment.provider]?.complete(call) ?: ProviderResult.Failed(ProviderFailure.ModelNotFound)

    override fun stream(call: ChatCall): Flow<ProviderStreamEvent> =
        ports[call.deployment.provider]?.stream(call)
            ?: flowOf(ProviderStreamEvent.Failed(ProviderFailure.ModelNotFound))
}
