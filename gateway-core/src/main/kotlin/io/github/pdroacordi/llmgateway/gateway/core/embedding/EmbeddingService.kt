package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelResolver
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.chat.UsageEstimator
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId

sealed interface EmbeddingOutcome {
    data class Answered(
        val deployment: Deployment,
        val list: EmbeddingList,
        val usage: Usage,
    ) : EmbeddingOutcome

    data class Rejected(
        val error: GatewayError,
    ) : EmbeddingOutcome

    /** The edge owns the `Retry-After`, so it builds the error. */
    data object Busy : EmbeddingOutcome
}

/** One embedding call end to end: resolve, call the port, fill in usage. RTE-02 adds retries and fallback here. */
class EmbeddingService(
    private val resolver: ModelResolver,
    private val port: EmbeddingModelPort,
    private val estimator: UsageEstimator,
) {
    suspend fun embed(
        requestId: RequestId,
        team: TeamId,
        request: EmbeddingRequest,
    ): EmbeddingOutcome {
        val deployment = resolver.resolve(request.model)
        val call = EmbeddingCall(requestId, team, deployment, request.input, request.extras)
        return when (val result = port.embed(call)) {
            is EmbeddingResult.Embedded -> {
                val usage = result.usage ?: estimator.estimate(request.input)
                EmbeddingOutcome.Answered(deployment, result.list, usage)
            }

            is EmbeddingResult.Failed -> {
                EmbeddingOutcome.Rejected(result.failure)
            }

            EmbeddingResult.Busy -> {
                EmbeddingOutcome.Busy
            }
        }
    }
}
