package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.getJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import io.github.pdroacordi.llmgateway.mockprovider.journal.Arrival
import io.github.pdroacordi.llmgateway.mockprovider.journal.JournalEntry
import io.github.pdroacordi.llmgateway.mockprovider.journal.Outcome
import io.github.pdroacordi.llmgateway.mockprovider.journal.RequestJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Reads after a response find its entry (docs/knowledge/bugs/mock-journal-entry-visible-after-the-response.md). An
 * arrival held open in the real journal is the state a client can see: it has the response, the entry is not written
 * yet. The bound is raised so that a slow machine cannot turn these tests into races of their own.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["mock.journal.read-wait=1s"],
)
@AutoConfigureWebTestClient
class JournalReadWaitIntegrationTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val journal: RequestJournal,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `stats waits for an entry still being written`() {
        completeSoon(journal.arrive())

        val stats = client.getJson("/__mock/stats")

        assertEquals(0, stats.json<Int>("$.inFlight"))
        assertEquals(1, stats.json<Int>("$.counts[0].requests"))
        assertEquals(1, stats.json<Int>("$.journal.size"))
    }

    @Test
    fun `requests waits for an entry still being written`() {
        val arrival = journal.arrive()
        completeSoon(arrival)

        val requests = client.getJson("/__mock/requests")

        assertEquals(listOf(arrival.seq.toInt()), requests.json<List<Int>>("$.data[*].seq"))
    }

    @Test
    fun `a request still held delays a read by the bound, then the read answers with what is there`() {
        val arrival = journal.arrive()
        val started = System.nanoTime()

        val stats = client.getJson("/__mock/stats")

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        journal.complete(arrival, entry(arrival))
        assertEquals(1, stats.json<Int>("$.inFlight"))
        assertTrue(elapsedMs >= READ_WAIT_MS) { "read answered after $elapsedMs ms" }
    }

    private fun completeSoon(arrival: Arrival) {
        CompletableFuture
            .delayedExecutor(COMPLETE_AFTER_MS, TimeUnit.MILLISECONDS)
            .execute { journal.complete(arrival, entry(arrival)) }
    }

    private fun entry(arrival: Arrival) =
        JournalEntry(
            seq = arrival.seq,
            route = "openai.chat",
            method = "POST",
            path = "/v1/chat/completions",
            model = "mock-chat",
            stream = false,
            includeUsage = false,
            receivedAt = arrival.receivedAt,
            durationMs = 1,
            headers = emptyMap(),
            credential = null,
            body = null,
            bodyTruncated = false,
            scenario = null,
            outcome = Outcome(kind = Outcome.Kind.COMPLETED, status = 200),
        )

    private companion object {
        const val READ_WAIT_MS = 1000L
        const val COMPLETE_AFTER_MS = 100L
    }
}
