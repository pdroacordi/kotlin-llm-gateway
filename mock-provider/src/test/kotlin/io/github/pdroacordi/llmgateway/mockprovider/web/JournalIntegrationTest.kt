package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.getJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messagesBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.postJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.concurrent.CompletableFuture

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mock.journal.max-body-bytes=400",
        "logging.level.io.github.pdroacordi.llmgateway.mockprovider=DEBUG",
    ],
)
@AutoConfigureWebTestClient
@ExtendWith(OutputCaptureExtension::class)
class JournalIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `one entry per request - redacted credential, kept trace headers, the body, and counters`(
        output: CapturedOutput,
    ) {
        val before = client.getJson("/__mock/stats").json<Int>("$.lastSeq")
        val body = chatBody("journal me")

        val response =
            client.chat(
                body,
                headers =
                    mapOf(
                        "authorization" to "Bearer $SECRET",
                        "traceparent" to TRACEPARENT,
                        "x-client-request-id" to "c-1",
                    ),
            )
        val requests = client.getJson("/__mock/requests?header=x-client-request-id:c-1")
        val stats = client.getJson("/__mock/stats")

        assertEquals(200, response.status.value())
        assertEquals(1, requests.json<List<Any>>("$.data").size)
        assertEquals(listOf("[redacted]"), requests.json<List<String>>("$.data[0].headers.authorization"))
        assertEquals(listOf(TRACEPARENT), requests.json<List<String>>("$.data[0].headers.traceparent"))
        assertEquals(listOf("c-1"), requests.json<List<String>>("$.data[0].headers.x-client-request-id"))
        // printf '%s' sk-test-abc | sha256sum
        assertEquals(
            mapOf("scheme" to "bearer", "fingerprint" to "e1c3928a"),
            requests.json<Map<String, String>>("$.data[0].credential"),
        )
        assertEquals(body, requests.json<String>("$.data[0].body"))
        assertEquals(false, requests.json<Boolean>("$.data[0].bodyTruncated"))
        assertEquals("openai.chat", requests.json<String>("$.data[0].route"))
        assertEquals("completed", requests.json<String>("$.data[0].outcome.kind"))
        assertEquals(200, requests.json<Int>("$.data[0].outcome.status"))
        assertNull(requests.json<Any?>("$.data[0].scenario"))
        assertEquals(before + 1, stats.json<Int>("$.lastSeq"))
        assertEquals(
            listOf(1),
            stats.json<List<Int>>("$.counts[?(@.route == 'openai.chat' && @.model == 'mock-chat')].requests"),
        )
        assertEquals(0, stats.json<Int>("$.inFlight"))
        val everything = listOf(response.bodyText(), requests, stats, client.getJson("/__mock/scenarios"), output.all)
        everything.forEach { assertFalse(SECRET in it) { "the credential leaked: $it" } }
    }

    @Test
    fun `x-api-key gets its own scheme on the Anthropic route`() {
        client.messages(messagesBody("x"), headers = mapOf("x-api-key" to "sk-other"))

        val requests = client.getJson("/__mock/requests?route=anthropic.messages")
        assertEquals(listOf("[redacted]"), requests.json<List<String>>("$.data[0].headers.x-api-key"))
        assertEquals(
            mapOf("scheme" to "x-api-key", "fingerprint" to "3dcad332"),
            requests.json<Map<String, String>>("$.data[0].credential"),
        )
    }

    @Test
    fun `an unknown path advances lastSeq and is journaled as unknown and rejected`() {
        val before = client.getJson("/__mock/stats").json<Int>("$.lastSeq")

        client
            .post()
            .uri("/v2/whatever")
            .exchange()
            .expectStatus()
            .isNotFound

        val requests = client.getJson("/__mock/requests?route=unknown")
        assertEquals(before + 1, client.getJson("/__mock/stats").json<Int>("$.lastSeq"))
        assertEquals("/v2/whatever", requests.json<String>("$.data[0].path"))
        assertEquals("rejected", requests.json<String>("$.data[0].outcome.kind"))
        assertEquals(404, requests.json<Int>("$.data[0].outcome.status"))
        assertNull(requests.json<String?>("$.data[0].credential"))
    }

    @Test
    fun `a body that fails validation is journaled with the model it named`() {
        val embeddings = client.postJson("/v1/embeddings", """{"model":"bad-embed","input":""}""")
        val chat = client.chat("""{"model":"bad-chat","messages":[]}""")
        val messages = client.messages("""{"model":"bad-claude","messages":[{"role":"user","content":"x"}]}""")

        listOf(embeddings, chat, messages).forEach { assertEquals(400, it.status.value()) }
        listOf("bad-embed", "bad-chat", "bad-claude").forEach { model ->
            val entry = client.getJson("/__mock/requests?model=$model")
            assertEquals(1, entry.json<List<Any>>("$.data").size) { model }
            assertEquals("rejected", entry.json<String>("$.data[0].outcome.kind"))
            assertEquals(400, entry.json<Int>("$.data[0].outcome.status"))
        }
        val counts = client.getJson("/__mock/stats")
        assertEquals(listOf(1), counts.json<List<Int>>("$.counts[?(@.model == 'bad-embed')].requests"))
    }

    @Test
    fun `admin calls are neither counted nor journaled`() {
        val before = client.getJson("/__mock/stats").json<Int>("$.lastSeq")
        client.getJson("/__mock/requests")
        client.getJson("/__mock/scenarios")

        assertEquals(before, client.getJson("/__mock/stats").json<Int>("$.lastSeq"))
    }

    @Test
    fun `a body over the cap is cut and flagged`() {
        client.chat(chatBody("x".repeat(1000), model = "big"))

        val entry = client.getJson("/__mock/requests?model=big")
        assertEquals(true, entry.json<Boolean>("$.data[0].bodyTruncated"))
        assertEquals(400, entry.json<String>("$.data[0].body").encodeToByteArray().size)
    }

    @Test
    fun `the journal records the scenario step, stream flags, pieces and tokens`() {
        client.putScenario("traced", """{"steps":[{"content":{"mode":"fixed","text":"a b c"}}]}""")
        client.chat(
            chatBody("x", model = "traced", extra = ""","stream":true,"stream_options":{"include_usage":true}"""),
        )

        val entry = client.getJson("/__mock/requests?model=traced")
        assertEquals(mapOf("model" to "traced", "step" to 0), entry.json<Map<String, Any>>("$.data[0].scenario"))
        assertEquals(true, entry.json<Boolean>("$.data[0].stream"))
        assertEquals(true, entry.json<Boolean>("$.data[0].includeUsage"))
        assertEquals(3, entry.json<Int>("$.data[0].outcome.piecesSent"))
        assertEquals(3, entry.json<Int>("$.data[0].outcome.completionTokens"))
        assertTrue(entry.json<Int>("$.data[0].outcome.promptTokens") > 0)
    }

    @Test
    fun `filters by since, model and header`() {
        val first = client.getJson("/__mock/stats").json<Int>("$.lastSeq")
        client.chat(
            chatBody("a", model = "m1"),
            headers =
                mapOf(
                    "authorization" to "Bearer k",
                    "x-client-request-id" to "one",
                ),
        )
        client.chat(
            chatBody("b", model = "m2"),
            headers =
                mapOf(
                    "authorization" to "Bearer k",
                    "x-client-request-id" to "two",
                ),
        )

        assertEquals(2, client.getJson("/__mock/requests?since=$first").json<List<Any>>("$.data").size)
        assertEquals(1, client.getJson("/__mock/requests?since=${first + 1}").json<List<Any>>("$.data").size)
        assertEquals(listOf("m2"), client.getJson("/__mock/requests?model=m2").json<List<String>>("$.data[*].model"))
        assertEquals(
            listOf("m1"),
            client.getJson("/__mock/requests?header=X-Client-Request-Id:one").json<List<String>>("$.data[*].model"),
        )
        assertEquals(1, client.getJson("/__mock/requests?limit=1").json<List<Any>>("$.data").size)
    }

    @Test
    fun `reset empties counts, scenarios and journal, keeps lastSeq, and drops a request in flight across it`() {
        // Outlasts the two bounded reads before the reset, each delayed by mock.journal.read-wait while it is held.
        client.putScenario("slow", """{"otherwise":{"latency":{"ttftMs":1500}}}""")
        client.chat(chatBody("before"))
        val inFlight =
            CompletableFuture.supplyAsync {
                client
                    .chat(
                        chatBody("in flight", model = "slow"),
                    ).status
                    .value()
            }
        awaitInFlight()
        val lastSeq = client.getJson("/__mock/stats").json<Int>("$.lastSeq")

        client.reset()
        assertEquals(200, inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS))

        val stats = client.getJson("/__mock/stats")
        assertEquals(lastSeq, stats.json<Int>("$.lastSeq"))
        assertEquals(emptyList<Any>(), stats.json<List<Any>>("$.counts"))
        assertEquals(0, stats.json<Int>("$.journal.size"))
        assertEquals(emptyList<Any>(), client.getJson("/__mock/requests").json<List<Any>>("$.data"))
        assertEquals(emptyList<Any>(), client.getJson("/__mock/scenarios").json<List<Any>>("$.data"))
    }

    private fun awaitInFlight() {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (client.getJson("/__mock/stats").json<Int>("$.inFlight") == 0) {
            check(System.nanoTime() < deadline) { "the slow request never arrived" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        // A fake key; the test asserts it never appears anywhere.
        const val SECRET = "sk-test-abc"
        const val TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        const val POLL_MS = 20L
    }
}
