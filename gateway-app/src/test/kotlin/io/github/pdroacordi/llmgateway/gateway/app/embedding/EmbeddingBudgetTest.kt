package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.CompletableDeferred
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The budget through real sockets. [FULL] reserves all of it: 3072 x 33 bytes, capped at `max-response-size`. Each test
 * ends the calls that exhausted it, then needs [FULL] to pass again: a reservation leaked on that path answers 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeEmbeddingModelPort.Wiring::class)
@Timeout(60)
@TestPropertySource(
    properties = [
        "llmgw.providers.openai.embeddings.max-response-size=64KB",
        "llmgw.providers.openai.embeddings.max-in-flight-bytes=64KB",
        "llmgw.providers.openai.embeddings.busy-retry-after=7s",
    ],
)
class EmbeddingBudgetTest(
    @LocalServerPort private val port: Int,
    @Autowired private val provider: FakeEmbeddingModelPort,
) {
    private val http = HttpClient.newHttpClient()

    @BeforeEach
    fun resetProvider() {
        provider.reset()
    }

    private fun post(
        body: String,
        path: String = "/v1/embeddings",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$path"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .timeout(CALL_LIMIT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** A client that sends a call and reads nothing; a small receive window keeps its reply in the server. */
    private fun rawCall(body: String): Socket {
        val socket = Socket()
        socket.receiveBufferSize = RECEIVE_WINDOW
        socket.soTimeout = CALL_LIMIT.toMillis().toInt()
        socket.connect(InetSocketAddress("127.0.0.1", port))
        val request =
            "POST /v1/embeddings HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: ${TestKeys.BEARER}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        socket.getOutputStream().apply {
            write(request.encodeToByteArray())
            flush()
        }
        return socket
    }

    private fun awaitCalls(count: Int) {
        await().atMost(WAIT).until { provider.calls.size >= count }
    }

    private fun assertBudgetFree() {
        provider.reset()
        await().atMost(WAIT).until { post(FULL).statusCode() == 200 }
    }

    @Test
    fun `calls that each fill the budget run one after the other`() {
        assertEquals(200, post(FULL).statusCode())
        assertBudgetFree()
        assertBudgetFree()
    }

    @Test
    fun `with the budget full a call gets 503 with Retry-After, and never reaches the provider`() {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate

        rawCall(FULL).use { socket ->
            awaitCalls(1)

            val refused = post(TINY)

            assertEquals(503, refused.statusCode()) { refused.body() }
            assertEquals("7", refused.headers().firstValue("retry-after").orElse(null))
            assertEquals("7000", refused.headers().firstValue("retry-after-ms").orElse(null))
            val error = JSON.readTree(refused.body()).get("error")
            assertEquals("gateway_overloaded", error.get("code").stringValue())
            assertEquals("server_error", error.get("type").stringValue())
            assertEquals(1, provider.calls.size)

            gate.complete(Unit)
            val answer = socket.getInputStream().readAllBytes().decodeToString()
            assertTrue(answer.startsWith("HTTP/1.1 200")) { answer.take(LOG_HEAD) }
        }
        assertBudgetFree()
    }

    @Test
    fun `a call the provider fails gives its bytes back`() {
        provider.answer = { EmbeddingResult.Failed(ProviderFailure.UpstreamError) }

        assertEquals(502, post(FULL).statusCode())

        assertBudgetFree()
    }

    @Test
    fun `a call that dies on a bug gives its bytes back`() {
        provider.answer = { error("a bug in the port") }

        assertEquals(500, post(FULL).statusCode())

        assertBudgetFree()
    }

    @Test
    fun `a client that disconnects during the upstream call gives its bytes back`() {
        provider.gate = CompletableDeferred()

        rawCall(FULL).use {
            awaitCalls(1)
            assertEquals(503, post(TINY).statusCode())
        }

        assertBudgetFree()
    }

    @Test
    fun `the bytes stay reserved through the response write, and a disconnect during it gives them back`() {
        val answered = AtomicBoolean()
        provider.answer = {
            answered.set(true)
            EmbeddingResult.Embedded(LARGE_LIST, FakeEmbeddingModelPort.USAGE)
        }

        rawCall(FULL).use {
            await().atMost(WAIT).untilTrue(answered)
            // Long enough for a release at the handler's return to happen, if that is where it were.
            Thread.sleep(WRITE_STARTED_MS)
            assertEquals(503, post(TINY).statusCode())
        }

        assertBudgetFree()
    }

    @Test
    fun `a body whose reservation cannot fit is refused before its parse`() {
        val unparsable = "{" + "x".repeat(LARGE_BODY_BYTES)

        val refused = post(unparsable)

        assertEquals(503, refused.statusCode()) { refused.body() }
        assertEquals(0, provider.calls.size)
        assertBudgetFree()
    }

    @Test
    fun `a body stays reserved through the call, and comes back with the reply's bytes`() {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val heldBody = """{"model":"mock-embedding","dimensions":1,"input":"${"x".repeat(HELD_BODY_BYTES)}"}"""

        rawCall(heldBody).use { socket ->
            awaitCalls(1)

            val refused = post(heldBody)

            assertEquals(503, refused.statusCode()) { refused.body() }
            assertEquals(1, provider.calls.size)
            gate.complete(Unit)
            val answer = socket.getInputStream().readAllBytes().decodeToString()
            assertTrue(answer.startsWith("HTTP/1.1 200")) { answer.take(LOG_HEAD) }
        }
        assertBudgetFree()
    }

    @Test
    fun `a path with a matrix parameter, which the route accepts, is held and released like any other`() {
        assertEquals(200, post(FULL, "/v1/embeddings;a=b").statusCode())

        assertBudgetFree()
    }

    private companion object {
        const val FULL = """{"model":"mock-embedding","input":"a","dimensions":3072}"""
        const val TINY = """{"model":"mock-embedding","input":"a","dimensions":1}"""
        const val RECEIVE_WINDOW = 4096

        // Twice the first is past the budget of 64 KB, and twice the second takes more than half of it.
        const val LARGE_BODY_BYTES = 40 * 1024
        const val HELD_BODY_BYTES = 20 * 1024
        const val LOG_HEAD = 200
        const val WRITE_STARTED_MS = 500L
        const val LARGE_REPLY_BYTES = 32 * 1024 * 1024
        val WAIT: Duration = Duration.ofSeconds(10)

        // A refusal is immediate, so a call this slow is a call that hangs: the budget must never wait.
        val CALL_LIMIT: Duration = Duration.ofSeconds(5)
        val JSON: JsonMapper = JsonMapper.builder().build()

        // Far more than loopback socket buffers hold, so the write cannot end while the client reads nothing.
        val LARGE_LIST =
            EmbeddingList(
                ModelName("mock-embedding"),
                listOf(Embedding(0, RawJson(ByteArray(LARGE_REPLY_BYTES) { '1'.code.toByte() }))),
            )
    }
}
