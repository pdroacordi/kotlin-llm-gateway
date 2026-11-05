package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId

/** One provider call. Adapters ignore [team]. */
data class EmbeddingCall(
    val requestId: RequestId,
    val team: TeamId,
    val deployment: Deployment,
    val input: EmbeddingInput,
    val extras: JsonValue.Object = JsonValue.Object.EMPTY,
) {
    init {
        requireNoTypedMember(extras, EmbeddingRequest.REQUEST_MEMBERS)
    }
}

sealed interface EmbeddingResult {
    /** [usage] is null when the provider omitted it. */
    data class Embedded(
        val list: EmbeddingList,
        val usage: Usage?,
    ) : EmbeddingResult

    data class Failed(
        val failure: ProviderFailure,
    ) : EmbeddingResult

    /** The gateway could not take the work: its pool's queue is full, or it is shutting down. No provider failed. */
    data object Busy : EmbeddingResult
}

/** One port per capability (ADR-GW-05-001). */
interface EmbeddingModelPort {
    /** Never throws for an expected failure. Cancellation of the caller cancels the upstream call. */
    suspend fun embed(call: EmbeddingCall): EmbeddingResult
}
