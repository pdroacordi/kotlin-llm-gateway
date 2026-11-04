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

    // The reads run on the upstream side of the buffer, and a late fail on the collector's. Written after undelivered:
    // a fail that sees the stream ended must find its terminal event.
    @Volatile
    private var state = State.OPEN
    private var finishSeen = false

    // Where the current feed takes the stream, once its events are in undelivered.
    private var feedEnd = State.OPEN

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
        feed.events.forEach { event -> if (feedEnd == State.OPEN) translate(event)?.let { events += it } }
        if (feed.overflowed && feedEnd == State.OPEN) events += failedInFeed(ProviderFailure.UpstreamError)
        if (feedEnd != State.OPEN) end(events, feedEnd)
        return events
    }

    fun finish(): List<ProviderStreamEvent> =
        when {
            state != State.OPEN -> {
                emptyList()
            }

            finishSeen -> {
                end(listOf(ProviderStreamEvent.Completed))
            }

            else -> {
                end(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)))
            }
        }

    /** The collector has the terminal event. */
    fun delivered() {
        undelivered = emptyList()
    }

    /** A terminal the collector never got wins, so a stream always ends in one; after one it got, nothing. */
    fun fail(failure: ProviderFailure): List<ProviderStreamEvent> =
        if (state == State.OPEN) {
            end(listOf(ProviderStreamEvent.Failed(failure)))
        } else {
            undelivered.also { undelivered = emptyList() }
        }

    private fun end(
        events: List<ProviderStreamEvent>,
        to: State = State.ENDED,
    ): List<ProviderStreamEvent> {
        undelivered = events
        state = to
        return events
    }

    private fun translate(event: SseEvent): ProviderStreamEvent? =
        when {
            event.data.isEmpty() -> {
                null
            }

            startsWithDone(event.data) -> {
                feedEnd = State.DRAINING
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
                failedInFeed(classifier.classifyInBand(read.fields))
            }

            ChunkRead.Unreadable -> {
                failedInFeed(ProviderFailure.UpstreamError)
            }
        }

    private fun failedInFeed(failure: ProviderFailure): ProviderStreamEvent {
        feedEnd = State.ENDED
        return ProviderStreamEvent.Failed(failure)
    }

    // A prefix, as both SDKs read it.
    private fun startsWithDone(data: ByteArray): Boolean =
        data.size >= DONE.size && DONE.indices.all { data[it] == DONE[it] }

    private companion object {
        val DONE = "[DONE]".encodeToByteArray()
    }
}
