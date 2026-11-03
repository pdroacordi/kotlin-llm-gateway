package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * Timing over real Netty sockets. Settles FND-03's unverified point: `writeAndFlushWith` with one inner publisher per
 * event flushes each event as it is produced (pieces arrive spread over the stream, not together at its end).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class LatencyIntegrationTest(
    @Autowired private val client: WebTestClient,
    @LocalServerPort private val port: Int,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    // Bounds the server enforces only: a wait never ends early, so each time is at least its configured sum, measured
    // from before the request is sent. Upper bounds keep 1 s or more of slack for a loaded CI runner.
    @Test
    fun `each stream event is flushed when produced - pieces arrive spread over the stream, the end after 1000 ms`() {
        client.putScenario(
            "mock-chat",
            """{"otherwise":{"latency":{"ttftMs":0,"perTokenMs":200},""" +
                """"content":{"mode":"fixed","text":"one two three four five"}}}""",
        )
        timedEvents(chatBody("warm up", model = WARM_UP, extra = STREAM))

        val events = timedEvents(chatBody("time me", extra = STREAM))
        val contents = events.filter { "\"content\":\"" in it.text && "\"role\"" !in it.text }
        val end = events.last()

        assertEquals(5, contents.size) { events.toString() }
        // Piece k follows k waits of 200 ms.
        contents.forEachIndexed { k, event -> assertTrue(event.atMs >= PER_TOKEN_MS * k) { events.toString() } }
        // Not buffered: a stream held back and sent at once would deliver the pieces within a few ms of each other.
        assertTrue(contents.last().atMs - contents.first().atMs >= 600) { events.toString() }
        assertTrue(end.text.startsWith("data: [DONE]")) { events.toString() }
        assertTrue(end.atMs >= PER_TOKEN_MS * 5) { events.toString() }
        println("FND-03 stream piece times (ms from request, perTokenMs=200): ${contents.map { it.atMs }}")
    }

    @Test
    fun `non-stream waits ttft plus perTokenMs times the completion tokens`() {
        client.putScenario(
            "mock-chat",
            """{"otherwise":{"latency":{"ttftMs":100,"perTokenMs":50},""" +
                """"content":{"mode":"fixed","text":"one two three four five"}}}""",
        )
        client.chat(chatBody("warm up", model = WARM_UP))

        val elapsed = timed { assertEquals(200, client.chat(chatBody("x")).status.value()) }

        // 100 + 5 x 50.
        assertTrue(elapsed in 350..1350) { "elapsed $elapsed ms" }
    }

    @Test
    fun `jitter stays within its bound`() {
        client.putScenario(
            "mock-chat",
            """{"otherwise":{"latency":{"ttftMs":100,"perTokenMs":0,"jitterMs":100},""" +
                """"content":{"mode":"fixed","text":"a"}}}""",
        )
        client.chat(chatBody("warm up", model = WARM_UP))

        // ttft + U[0, jitter], then perTokenMs x 1 + U[0, jitter]: 100 to 300 ms of waiting.
        repeat(5) {
            val elapsed = timed { client.chat(chatBody("x")) }
            assertTrue(elapsed in 100..1300) { "elapsed $elapsed ms" }
        }
    }

    @Test
    fun `an error waits ttft before it is sent`() {
        client.putScenario("mock-chat", """{"otherwise":{"latency":{"ttftMs":300},"error":{"status":503}}}""")

        val elapsed = timed { assertEquals(503, client.chat(chatBody("x")).status.value()) }

        assertTrue(elapsed >= 300) { "elapsed $elapsed ms" }
    }

    private data class TimedEvent(
        val atMs: Double,
        val text: String,
    )

    // Raw buffers, so each event's arrival time is when the buffer that completed it arrived.
    private fun timedEvents(body: String): List<TimedEvent> {
        val start = System.nanoTime()
        val chunks =
            WebClient
                .create("http://localhost:$port")
                .post()
                .uri("/v1/chat/completions")
                .header("authorization", "Bearer $KEY")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToFlux { it.bodyToFlux(DataBuffer::class.java) }
                .map { buffer ->
                    val text = buffer.toString(Charsets.UTF_8)
                    DataBufferUtils.release(buffer)
                    (System.nanoTime() - start) / NANOS_PER_MS to text
                }.collectList()
                .block(Duration.ofSeconds(10))
                .orEmpty()
        val events = mutableListOf<TimedEvent>()
        val pending = StringBuilder()
        chunks.forEach { (atMs, text) ->
            pending.append(text)
            while (true) {
                val end = pending.indexOf("\n\n")
                if (end < 0) break
                events += TimedEvent(atMs, pending.substring(0, end + 2))
                pending.delete(0, end + 2)
            }
        }
        return events
    }

    private fun timed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return ((System.nanoTime() - start) / NANOS_PER_MS).toLong()
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
        const val PER_TOKEN_MS = 200.0
        const val STREAM = ""","stream":true"""

        // A model without a scenario: its request warms the client's connection and the server's code paths.
        const val WARM_UP = "warm-up"
    }
}
