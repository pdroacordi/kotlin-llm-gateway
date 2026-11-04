package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId

/** One provider call. Grows by field (team in GW-04), so the port signature does not change. */
data class ChatCall(
    val requestId: RequestId,
    val deployment: Deployment,
    val request: ChatRequest,
)

sealed interface ProviderResult {
    /** [usage] is null when the provider omitted it. */
    data class Completed(
        val completion: ChatCompletion,
        val usage: Usage?,
    ) : ProviderResult

    data class Failed(
        val failure: ProviderFailure,
    ) : ProviderResult
}

/** One port per capability; only adapters see a provider's types. GW-02 adds `stream`. */
interface ChatModelPort {
    /** Never throws for an expected failure. Cancellation of the caller cancels the upstream call. */
    suspend fun complete(call: ChatCall): ProviderResult
}
