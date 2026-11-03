package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.journal.Arrival
import io.github.pdroacordi.llmgateway.mockprovider.journal.Credential
import io.github.pdroacordi.llmgateway.mockprovider.journal.JournalEntry
import io.github.pdroacordi.llmgateway.mockprovider.journal.Outcome
import io.github.pdroacordi.llmgateway.mockprovider.journal.ScenarioRef
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import org.springframework.web.server.ServerWebExchange
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.TimeSource

/** What the filter saw: route id, request line, redacted headers and the credential's fingerprint. */
data class Received(
    val route: String,
    val method: String,
    val path: String,
    val headers: Map<String, List<String>>,
    val credential: Credential?,
)

/** Whether request bodies are kept, and up to how many bytes (`mock.journal.*`). */
data class BodyCapture(
    val enabled: Boolean,
    val maxBytes: Int,
)

/**
 * What the journal will say about one counted request, filled in by the filter, the handler and a streaming body.
 * The entry is written once, when the last of them leaves: the filter always takes part, a stream joins when it starts
 * ([join]), so whichever of the two ends last writes it, whatever the order of cancellation.
 */
class RequestRecord(
    val arrival: Arrival,
    val received: Received,
    private val capture: BodyCapture,
    private val onDone: (RequestRecord) -> Unit,
) {
    val api: ProviderApi = ProviderApi.forPath(received.path)

    /** The provider-style request ID, also in `x-request-id` / `request-id` and Anthropic error bodies. */
    val requestId: String = "req_%032x".format(Locale.ROOT, arrival.seq)

    @Volatile var model: String? = null

    @Volatile var stream: Boolean? = null

    @Volatile var includeUsage: Boolean? = null

    @Volatile var scenario: ScenarioRef? = null

    @Volatile var piecesSent: Int? = null

    @Volatile var promptTokens: Int? = null

    @Volatile var completionTokens: Int? = null

    @Volatile var body: String? = null
        private set

    @Volatile var bodyTruncated: Boolean = false
        private set

    private val started = TimeSource.Monotonic.markNow()
    private val outcome = AtomicReference<Outcome.Kind?>(null)

    @Volatile private var status: Int? = null
    private val parties = AtomicInteger(1)

    fun captureBody(text: String) {
        if (!capture.enabled) return
        val bytes = text.encodeToByteArray()
        if (bytes.size <= capture.maxBytes) {
            body = text
            return
        }
        // Back off to a UTF-8 character boundary (continuation bytes are 10xxxxxx).
        var end = capture.maxBytes
        while (end > 0 && (bytes[end].toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION) end--
        body = bytes.decodeToString(0, end)
        bodyTruncated = true
    }

    /** Sets the outcome, replacing any earlier one: the serving code knows best what happened. */
    fun finish(
        kind: Outcome.Kind,
        status: Int? = this.status,
    ) {
        outcome.set(kind)
        this.status = status
    }

    /** Sets the outcome only if none is set: the filter's fallback after the handler. */
    fun finishIfAbsent(
        kind: Outcome.Kind,
        status: Int?,
    ) {
        if (outcome.compareAndSet(null, kind)) this.status = status
    }

    fun status(status: Int) {
        this.status = status
    }

    /** A streaming body takes part; false when the entry is already written (the client is gone). */
    fun join(): Boolean {
        while (true) {
            val current = parties.get()
            if (current == 0) return false
            if (parties.compareAndSet(current, current + 1)) return true
        }
    }

    fun leave() {
        if (parties.decrementAndGet() == 0) onDone(this)
    }

    fun toEntry(): JournalEntry =
        JournalEntry(
            seq = arrival.seq,
            route = received.route,
            method = received.method,
            path = received.path,
            model = model,
            stream = stream,
            includeUsage = includeUsage,
            receivedAt = arrival.receivedAt,
            durationMs = started.elapsedNow().inWholeMilliseconds,
            headers = received.headers,
            credential = received.credential,
            body = body,
            bodyTruncated = bodyTruncated,
            scenario = scenario,
            outcome =
                Outcome(
                    kind = outcome.get() ?: Outcome.Kind.COMPLETED,
                    status = status,
                    piecesSent = piecesSent,
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                ),
        )

    companion object {
        const val ATTRIBUTE = "io.github.pdroacordi.llmgateway.mockprovider.web.RequestRecord"
        private const val UTF8_CONTINUATION_MASK = 0xC0
        private const val UTF8_CONTINUATION = 0x80

        fun of(exchange: ServerWebExchange): RequestRecord? = exchange.attributes[ATTRIBUTE] as? RequestRecord
    }
}
