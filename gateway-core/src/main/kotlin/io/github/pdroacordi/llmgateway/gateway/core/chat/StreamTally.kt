package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure

/**
 * The state machine behind `ChatCompletionService.stream` (GW-02 section 5), which holds counts and never text. A
 * terminal outcome beats a late cancellation: the provider's answer is then complete and its usage known.
 */
class StreamTally(
    private val request: ChatRequest,
    private val deployment: Deployment,
    private val estimator: UsageEstimator,
) {
    private val askedForUsage = request.streamOptions?.includeUsage == true
    private val characters = mutableMapOf<Int, Long>()
    private var providerUsage: Usage? = null
    private var last: ChatChunk? = null
    private var started = false
    private var chunks = 0
    private var outcome: StreamOutcome? = null

    /** False until a terminal event was handled. */
    val ended: Boolean get() = outcome != null

    fun on(event: ProviderStreamEvent): List<ChatStreamEvent> {
        if (ended) return emptyList()
        return when (event) {
            is ProviderStreamEvent.Chunk -> chunk(event.chunk)
            ProviderStreamEvent.Completed -> completed()
            is ProviderStreamEvent.Failed -> failed(event.failure)
        }
    }

    fun summary(cancelled: Boolean): StreamSummary {
        val ending = outcome ?: if (cancelled) StreamOutcome.Cancelled else StreamOutcome.Crashed
        return StreamSummary(deployment, ending, usage(), started, chunks)
    }

    private fun chunk(chunk: ChatChunk): List<ChatStreamEvent> {
        chunk.choices.forEach { choice ->
            characters.merge(
                choice.index,
                choice.delta.content
                    .orEmpty()
                    .length
                    .toLong(),
                Long::plus,
            )
        }
        chunk.usage?.let { providerUsage = it }
        return when {
            chunk.choices.isEmpty() && chunk.usage != null && !askedForUsage -> {
                emptyList()
            }

            started -> {
                listOf(forward(chunk))
            }

            else -> {
                started = true
                listOf(ChatStreamEvent.Started(deployment), forward(chunk))
            }
        }
    }

    private fun forward(chunk: ChatChunk): ChatStreamEvent {
        last = chunk
        chunks++
        return ChatStreamEvent.Chunk(chunk)
    }

    private fun completed(): List<ChatStreamEvent> {
        val latest = last ?: return failed(ProviderFailure.UpstreamError)
        outcome = StreamOutcome.Completed
        val usage = usage()
        val done = ChatStreamEvent.Completed(usage)
        val synthesise = askedForUsage && providerUsage == null
        return if (synthesise) {
            listOf(forward(ChatChunk(latest.id, latest.created, latest.model, emptyList(), usage)), done)
        } else {
            listOf(done)
        }
    }

    private fun failed(failure: ProviderFailure): List<ChatStreamEvent> {
        outcome = StreamOutcome.Failed(failure)
        return listOf(ChatStreamEvent.Failed(failure))
    }

    private fun usage(): Usage = providerUsage ?: estimator.estimate(request, characters.values)
}
