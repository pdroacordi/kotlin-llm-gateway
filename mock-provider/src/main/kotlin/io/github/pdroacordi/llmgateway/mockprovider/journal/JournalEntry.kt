package io.github.pdroacordi.llmgateway.mockprovider.journal

import java.time.Instant

data class Outcome(
    val kind: Kind,
    val status: Int?,
    val piecesSent: Int? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
) {
    enum class Kind(
        val wire: String,
    ) {
        COMPLETED("completed"),
        ERROR_INJECTED("error_injected"),

        /** Validation, credential or framework error. */
        REJECTED("rejected"),
        CLIENT_CANCELLED("client_cancelled"),

        /** A `disconnect` fault. */
        ABORTED("aborted"),
        STREAM_ERROR("stream_error"),
        MALFORMED("malformed"),

        /** A `hang` or `stall` that reached `mock.max-hold`. */
        TIMED_OUT("timed_out"),
    }
}

data class ScenarioRef(
    val model: String,
    val step: Int?,
)

/** One received request, immutable, written once at completion. [headers] are already redacted. */
data class JournalEntry(
    val seq: Long,
    val route: String,
    val method: String,
    val path: String,
    val model: String?,
    val stream: Boolean?,
    val includeUsage: Boolean?,
    val receivedAt: Instant,
    val durationMs: Long,
    val headers: Map<String, List<String>>,
    val credential: Credential?,
    val body: String?,
    val bodyTruncated: Boolean,
    val scenario: ScenarioRef?,
    val outcome: Outcome,
)

/** `GET /__mock/requests`: every non-null field must match; [since] is exclusive; header names ignore case. */
data class JournalFilter(
    val route: String? = null,
    val model: String? = null,
    val since: Long? = null,
    val headers: List<Pair<String, String>> = emptyList(),
    val limit: Int = DEFAULT_LIMIT,
) {
    fun matches(entry: JournalEntry): Boolean =
        (route == null || entry.route == route) &&
            (model == null || entry.model == model) &&
            (since == null || entry.seq > since) &&
            headers.all { (name, value) -> entry.headers[name.lowercase()].orEmpty().contains(value) }

    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
    }
}

data class CountRow(
    val route: String,
    val model: String?,
    val requests: Long,
    val outcomes: Map<Outcome.Kind, Long>,
)

data class JournalStats(
    val lastSeq: Long,
    val inFlight: Int,
    val counts: List<CountRow>,
    val capacity: Int,
    val journalSize: Int,
    val dropped: Long,
)
