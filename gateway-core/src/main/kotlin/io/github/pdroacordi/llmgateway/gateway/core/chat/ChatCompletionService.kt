package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId

sealed interface ChatOutcome {
    data class Answered(
        val deployment: Deployment,
        val completion: ChatCompletion,
        val usage: Usage,
    ) : ChatOutcome

    data class Rejected(
        val error: GatewayError,
    ) : ChatOutcome
}

/** One chat call end to end: resolve, call the port, fill in usage. RTE-02 adds retries and fallback here. */
class ChatCompletionService(
    private val resolver: ModelResolver,
    private val port: ChatModelPort,
    private val estimator: UsageEstimator,
) {
    suspend fun complete(
        requestId: RequestId,
        request: ChatRequest,
    ): ChatOutcome {
        val deployment = resolver.resolve(request.model)
        return when (val result = port.complete(ChatCall(requestId, deployment, request))) {
            is ProviderResult.Completed -> {
                val usage = result.usage ?: estimator.estimate(request, result.completion)
                ChatOutcome.Answered(deployment, result.completion, usage)
            }

            is ProviderResult.Failed -> {
                ChatOutcome.Rejected(result.failure)
            }
        }
    }
}
