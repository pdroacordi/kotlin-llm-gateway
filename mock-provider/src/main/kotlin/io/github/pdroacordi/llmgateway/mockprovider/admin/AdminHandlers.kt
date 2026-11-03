package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScenarioStore
import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import io.github.pdroacordi.llmgateway.mockprovider.journal.JournalEntry
import io.github.pdroacordi.llmgateway.mockprovider.journal.JournalFilter
import io.github.pdroacordi.llmgateway.mockprovider.journal.RequestJournal
import io.github.pdroacordi.llmgateway.mockprovider.web.awaitDelay
import io.github.pdroacordi.llmgateway.mockprovider.web.readJson
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import kotlin.time.TimeSource
import kotlin.time.toKotlinDuration

/** The mock's own control surface under `/__mock`: not counted, not journaled (FND-03 section 4, Admin API). */
class AdminHandlers(
    private val mapper: JsonMapper,
    private val store: ScenarioStore,
    private val journal: RequestJournal,
    private val scenarios: ScenarioJson,
    readWait: Duration,
) {
    private val readWait = readWait.toKotlinDuration()

    suspend fun putScenario(request: ServerRequest): ServerResponse {
        val scenario = scenarios.parse(request.readJson(mapper))
        store.put(request.pathVariable("model"), scenario)
        return ServerResponse.noContent().buildAndAwait()
    }

    suspend fun listScenarios(): ServerResponse {
        val root = mapper.createObjectNode()
        val data = root.putArray("data")
        store.snapshot().forEach { state ->
            val written = scenarios.write(state.scenario)
            data
                .addObject()
                .put("model", state.model)
                .set("steps", written.get("steps"))
                .set("otherwise", written.get("otherwise"))
                .put("stepsConsumed", state.stepsConsumed)
        }
        return json(root)
    }

    suspend fun deleteScenario(request: ServerRequest): ServerResponse {
        store.remove(request.pathVariable("model"))
        return ServerResponse.noContent().buildAndAwait()
    }

    suspend fun deleteScenarios(): ServerResponse {
        store.clear()
        return ServerResponse.noContent().buildAndAwait()
    }

    suspend fun requests(request: ServerRequest): ServerResponse {
        awaitJournalSettled()
        val root = mapper.createObjectNode()
        val data = root.putArray("data")
        journal.query(filter(request)).forEach { data.add(entry(it)) }
        return json(root)
    }

    suspend fun stats(): ServerResponse {
        awaitJournalSettled()
        val stats = journal.stats()
        val root = mapper.createObjectNode().put("lastSeq", stats.lastSeq).put("inFlight", stats.inFlight)
        val counts = root.putArray("counts")
        stats.counts.forEach { row ->
            val node =
                counts
                    .addObject()
                    .put("route", row.route)
                    .put("model", row.model)
                    .put("requests", row.requests)
            val outcomes = node.putObject("outcomes")
            row.outcomes.entries
                .sortedBy { it.key.ordinal }
                .forEach { (kind, count) -> outcomes.put(kind.wire, count) }
        }
        root
            .putObject("journal")
            .put("capacity", stats.capacity)
            .put("size", stats.journalSize)
            .put("dropped", stats.dropped)
        return json(root)
    }

    /** Removes scenarios, counts and the journal; `lastSeq` is never rewound. */
    suspend fun reset(): ServerResponse {
        store.clear()
        journal.reset()
        return ServerResponse.noContent().buildAndAwait()
    }

    // An entry is written after its response reaches the client, so a read right after a response could miss it.
    // `complete` decrements inFlight last, so 0 means every entry is visible; a held request delays a read by
    // `readWait` at most (docs/knowledge/bugs/mock-journal-entry-visible-after-the-response.md).
    private suspend fun awaitJournalSettled() {
        val started = TimeSource.Monotonic.markNow()
        while (journal.inFlight() != 0 && started.elapsedNow() < readWait) awaitDelay(SETTLED_POLL_MS)
    }

    private fun filter(request: ServerRequest): JournalFilter {
        val params = request.queryParams()
        val since =
            params.getFirst("since")?.let {
                it.toLongOrNull()?.takeIf { value -> value >= 0 }
                    ?: reject("since", "'since' must be an arrival sequence.")
            }
        val limit =
            params.getFirst("limit")?.let {
                it.toIntOrNull()?.takeIf { value -> value in 1..JournalFilter.MAX_LIMIT }
                    ?: reject("limit", "'limit' must be an integer from 1 to ${JournalFilter.MAX_LIMIT}.")
            }
        val headers =
            params["header"].orEmpty().map { pair ->
                val name = pair.substringBefore(':', missingDelimiterValue = "")
                if (name.isBlank()) reject("header", "'header' must be <name>:<value>.")
                name.trim() to pair.substringAfter(':')
            }
        return JournalFilter(
            route = params.getFirst("route"),
            model = params.getFirst("model"),
            since = since,
            headers = headers,
            limit = limit ?: JournalFilter.DEFAULT_LIMIT,
        )
    }

    private fun entry(entry: JournalEntry): ObjectNode {
        val node =
            mapper
                .createObjectNode()
                .put("seq", entry.seq)
                .put("route", entry.route)
                .put("method", entry.method)
                .put("path", entry.path)
                .put("model", entry.model)
                .put("stream", entry.stream)
                .put("includeUsage", entry.includeUsage)
                .put("receivedAt", entry.receivedAt.toString())
                .put("durationMs", entry.durationMs)
        val headers = node.putObject("headers")
        entry.headers.forEach { (name, values) -> headers.putArray(name).also { array -> values.forEach(array::add) } }
        val credential = entry.credential
        if (credential == null) {
            node.putNull("credential")
        } else {
            node
                .putObject(
                    "credential",
                ).put("scheme", credential.scheme.wire)
                .put("fingerprint", credential.fingerprint)
        }
        node.put("body", entry.body).put("bodyTruncated", entry.bodyTruncated)
        val scenario = entry.scenario
        if (scenario == null) {
            node.putNull("scenario")
        } else {
            node.putObject("scenario").put("model", scenario.model).put("step", scenario.step)
        }
        val outcome = entry.outcome
        node
            .putObject("outcome")
            .put("kind", outcome.kind.wire)
            .put("status", outcome.status)
            .put("piecesSent", outcome.piecesSent)
            .put("promptTokens", outcome.promptTokens)
            .put("completionTokens", outcome.completionTokens)
        return node
    }

    private suspend fun json(root: ObjectNode): ServerResponse =
        ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(mapper.writeValueAsString(root))

    private companion object {
        const val SETTLED_POLL_MS = 1L
    }
}
