package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEvent
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEventParser

/** What one upstream event gave, and where it leaves the stream. */
internal sealed interface Decoded {
    val events: List<ProviderStreamEvent>

    data class Open(
        override val events: List<ProviderStreamEvent>,
    ) : Decoded

    /** The provider said it is done: the rest is read to EOF, so the connection returns to the pool. */
    data class Draining(
        override val events: List<ProviderStreamEvent>,
    ) : Decoded

    data class Ended(
        override val events: List<ProviderStreamEvent>,
    ) : Decoded
}

/** One instance per stream; the provider's part of a stream, with no threads or timers in it. */
internal interface StreamDecoder {
    fun decode(event: SseEvent): Decoded

    /** The body ended in the open state. */
    fun atEnd(): List<ProviderStreamEvent>

    /** The in-band error's fields, for the adapter's log line. */
    val errorFields: ProviderErrorFields?
}

/** Upstream bytes to [ProviderStreamEvent]s through a [StreamDecoder], one instance per stream (GW-02 section 5). */
internal open class StreamLifecycle(
    private val decoder: StreamDecoder,
    maxEventBytes: Int,
) {
    private enum class State { OPEN, DRAINING, ENDED }

    private val parser = SseEventParser(maxEventBytes)

    // The reads run on the upstream side of the buffer, and a late fail on the collector's. Written after undelivered:
    // a fail that sees the stream ended must find its terminal event.
    @Volatile
    private var state = State.OPEN

    // The feed that ended the stream, until the collector has it: Flux.timeout drops an item that loses to its timer.
    @Volatile
    private var undelivered: List<ProviderStreamEvent> = emptyList()

    /** True after a failure or a stream with no terminal marker, when nothing more is worth reading. */
    val ended: Boolean get() = state == State.ENDED

    /** True after the provider's terminal marker: the rest is read to EOF (ADR-GW-02-006). */
    val draining: Boolean get() = state == State.DRAINING

    /** Set by an in-band error, for the adapter's log line. */
    val errorFields: ProviderErrorFields? get() = decoder.errorFields

    fun feed(bytes: ByteArray): List<ProviderStreamEvent> {
        if (state != State.OPEN) return emptyList()
        val feed = parser.feed(bytes)
        val events = mutableListOf<ProviderStreamEvent>()
        var feedEnd = State.OPEN
        feed.events.forEach { event ->
            if (feedEnd == State.OPEN) {
                val decoded = decoder.decode(event)
                events += decoded.events
                feedEnd = decoded.state()
            }
        }
        if (feed.overflowed && feedEnd == State.OPEN) {
            events += ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)
            feedEnd = State.ENDED
        }
        if (feedEnd != State.OPEN) end(events, feedEnd)
        return events
    }

    fun finish(): List<ProviderStreamEvent> = if (state == State.OPEN) end(decoder.atEnd()) else emptyList()

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

    private fun Decoded.state(): State =
        when (this) {
            is Decoded.Open -> State.OPEN
            is Decoded.Draining -> State.DRAINING
            is Decoded.Ended -> State.ENDED
        }
}
