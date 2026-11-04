package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

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

    /** Cold. [onEnd] must not suspend: it can run in a cancelled coroutine. */
    fun stream(
        requestId: RequestId,
        request: ChatRequest,
        onEnd: (StreamSummary) -> Unit = {},
    ): Flow<ChatStreamEvent> =
        flow {
            val deployment = resolver.resolve(request.model)
            val tally = StreamTally(request, deployment, estimator)
            try {
                port.stream(ChatCall(requestId, deployment, request)).collect { event ->
                    tally.on(event).forEach { emit(it) }
                }
                // Otherwise the response would be an empty 200: no event ever reaches the frames.
                check(tally.ended) { "The port ended a stream with no terminal event." }
            } finally {
                // Recorded, never caught: a cancellation propagates unchanged.
                onEnd(tally.summary(cancelled = !currentCoroutineContext().isActive))
            }
        }
}
