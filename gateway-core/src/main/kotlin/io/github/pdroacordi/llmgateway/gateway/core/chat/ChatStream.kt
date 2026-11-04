package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure

/** Zero or more [Chunk], then exactly one terminal event, then the flow completes (ADR-019). */
sealed interface ProviderStreamEvent {
    data class Chunk(
        val chunk: ChatChunk,
    ) : ProviderStreamEvent

    data object Completed : ProviderStreamEvent

    data class Failed(
        val failure: ProviderFailure,
    ) : ProviderStreamEvent
}

sealed interface ChatStreamEvent {
    /** The first chunk is in hand: the last moment a status or header can change. */
    data class Started(
        val deployment: Deployment,
    ) : ChatStreamEvent

    data class Chunk(
        val chunk: ChatChunk,
    ) : ChatStreamEvent

    data class Completed(
        val usage: Usage,
    ) : ChatStreamEvent

    data class Failed(
        val failure: ProviderFailure,
    ) : ChatStreamEvent
}

sealed interface StreamOutcome {
    data object Completed : StreamOutcome

    data class Failed(
        val failure: ProviderFailure,
    ) : StreamOutcome

    data object Cancelled : StreamOutcome

    /** An exception left the flow: a bug, logged by whoever caught it. */
    data object Crashed : StreamOutcome
}

/** Reported exactly once per stream, whatever ended it. [usage] is estimated unless the provider reported it. */
data class StreamSummary(
    val deployment: Deployment,
    val outcome: StreamOutcome,
    val usage: Usage,
    val started: Boolean,
    val chunks: Int,
)
