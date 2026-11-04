package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEvent
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEventParser

/** Upstream bytes to [ProviderStreamEvent]s, one instance per stream (GW-02 section 5). */
internal class OpenAiStreamTranslator(
    private val reader: OpenAiChunkReader,
    private val classifier: OpenAiFailureClassifier,
    maxEventBytes: Int,
) {
    private enum class State { OPEN, DRAINING, ENDED }

    private val parser = SseEventParser(maxEventBytes)

    // The reads run on the upstream side of the buffer, and a late fail on the collector's.
    @Volatile
    private var state = State.OPEN
    private var finishSeen = false

    // The feed that ended the stream, until the collector has it: Flux.timeout drops an item that loses to its timer.
    @Volatile
    private var undelivered: List<ProviderStreamEvent> = emptyList()

    /** True after a failure or a stream with no `[DONE]`, when nothing more is worth reading. */
    val ended: Boolean get() = state == State.ENDED

    /** True after `[DONE]`: the rest is read to EOF so the connection returns to the pool (ADR-GW-02-006). */
    val draining: Boolean get() = state == State.DRAINING

    /** Set by an in-band error, for the adapter's log line. */
    @Volatile
    var errorFields: ProviderErrorFields? = null
        private set

    fun feed(bytes: ByteArray): List<ProviderStreamEvent> {
        if (state != State.OPEN) return emptyList()
        val feed = parser.feed(bytes)
        val events = mutableListOf<ProviderStreamEvent>()
        feed.events.forEach { event -> if (state == State.OPEN) translate(event)?.let { events += it } }
        if (feed.overflowed && state == State.OPEN) events += failed(ProviderFailure.UpstreamError)
        if (state != State.OPEN) undelivered = events
        return events
    }

    fun finish(): List<ProviderStreamEvent> =
        when {
            state != State.OPEN -> {
                emptyList()
            }

            finishSeen -> {
                state = State.ENDED
                pending(ProviderStreamEvent.Completed)
            }

            else -> {
                pending(failed(ProviderFailure.UpstreamError))
            }
        }

    /** The collector has the terminal event. */
    fun delivered() {
        undelivered = emptyList()
    }

    /** A terminal the collector never got wins, so a stream always ends in one; after one it got, nothing. */
    fun fail(failure: ProviderFailure): List<ProviderStreamEvent> =
        if (state == State.OPEN) {
            pending(failed(failure))
        } else {
            undelivered.also { undelivered = emptyList() }
        }

    private fun pending(event: ProviderStreamEvent): List<ProviderStreamEvent> = listOf(event).also { undelivered = it }

    private fun translate(event: SseEvent): ProviderStreamEvent? =
        when {
            event.data.isEmpty() -> {
                null
            }

            startsWithDone(event.data) -> {
                state = State.DRAINING
                ProviderStreamEvent.Completed
            }

            else -> {
                read(event.data)
            }
        }

    private fun read(data: ByteArray): ProviderStreamEvent =
        when (val read = reader.read(data)) {
            is ChunkRead.Read -> {
                finishSeen = finishSeen || read.chunk.choices.any { it.finishReason != null }
                ProviderStreamEvent.Chunk(read.chunk)
            }

            is ChunkRead.InBandError -> {
                errorFields = read.fields
                failed(classifier.classifyInBand(read.fields))
            }

            ChunkRead.Unreadable -> {
                failed(ProviderFailure.UpstreamError)
            }
        }

    private fun failed(failure: ProviderFailure): ProviderStreamEvent {
        state = State.ENDED
        return ProviderStreamEvent.Failed(failure)
    }

    // A prefix, as both SDKs read it.
    private fun startsWithDone(data: ByteArray): Boolean =
        data.size >= DONE.size && DONE.indices.all { data[it] == DONE[it] }

    private companion object {
        val DONE = "[DONE]".encodeToByteArray()
    }
}
