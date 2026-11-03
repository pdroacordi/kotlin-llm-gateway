package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.getJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messagesBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.Socket
import java.time.Duration
import java.util.Collections

/**
 * Faults over real Netty sockets. Settles FND-03's unverified points: a client going away cancels the handler in its
 * `delay` and a running stream (`client_cancelled`), and an error signalled after commit makes Reactor Netty close the
 * connection without the terminating chunk (premature close).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["mock.max-hold=3s"],
)
@AutoConfigureWebTestClient
class FaultInjectionIntegrationTest(
    @Autowired private val client: WebTestClient,
    @LocalServerPort private val port: Int,
) {
    enum class Api(
        val path: String,
        val model: String,
    ) {
        OPENAI("/v1/chat/completions", "fault-chat"),
        ANTHROPIC("/v1/messages", "fault-claude"),
    }

    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `two of ten concurrent requests get the 429 step with its headers and body`() {
        client.putScenario(
            "mock-chat",
            """{"steps":[{"error":{"status":429,"retryAfterSeconds":2,""" +
                """"headers":{"x-ratelimit-remaining-requests":"0"}},"times":2}]}""",
        )

        val results =
            Flux
                .range(0, 10)
                .flatMap(
                    {
                        spec(Api.OPENAI, chatBody("concurrent")).exchangeToMono { response ->
                            response.toEntity(String::class.java)
                        }
                    },
                    10,
                ).collectList()
                .block(Duration.ofSeconds(10))
                .orEmpty()
        val limited = results.filter { it.statusCode.value() == 429 }

        assertEquals(10, results.size)
        assertEquals(2, limited.size) { results.map { it.statusCode }.toString() }
        assertEquals(8, results.count { it.statusCode.value() == 200 })
        limited.forEach { response ->
            assertEquals("2", response.headers.getFirst("retry-after"))
            assertEquals("0", response.headers.getFirst("x-ratelimit-remaining-requests"))
            assertEquals(
                """{"error":{"message":"mock-provider injected HTTP 429.","type":"requests","param":null,""" +
                    """"code":"rate_limit_exceeded"}}""",
                response.body,
            )
        }
    }

    @Test
    fun `a 503 step, then success`() {
        client.putScenario("mock-chat", """{"steps":[{"error":{"status":503,"retryAfterMs":250}}]}""")

        val failed = client.chat(chatBody("retry me"))
        val recovered = client.chat(chatBody("retry me"))

        assertEquals(503, failed.status.value())
        assertEquals("250", failed.responseHeaders.getFirst("retry-after-ms"))
        assertEquals("service_unavailable_error", failed.json<String>("$.error.type"))
        assertEquals("server_is_overloaded", failed.json<String>("$.error.code"))
        assertEquals(200, recovered.status.value())
    }

    @ParameterizedTest(name = "{0}: OpenAI {1}/{2}, Anthropic {3}")
    @CsvSource(
        nullValues = ["null"],
        value = [
            "400, invalid_request_error, null, invalid_request_error",
            "401, invalid_request_error, invalid_api_key, authentication_error",
            "402, invalid_request_error, null, billing_error",
            "403, invalid_request_error, null, permission_error",
            "404, invalid_request_error, model_not_found, not_found_error",
            "409, invalid_request_error, null, conflict_error",
            "413, invalid_request_error, null, request_too_large",
            "429, requests, rate_limit_exceeded, rate_limit_error",
            "500, server_error, null, api_error",
            "503, service_unavailable_error, server_is_overloaded, api_error",
            "504, server_error, null, timeout_error",
            "529, server_error, null, overloaded_error",
        ],
    )
    fun `each status gets the default body on both routes`(
        status: Int,
        openAiType: String,
        openAiCode: String?,
        anthropicType: String,
    ) {
        client.putScenario("*", """{"otherwise":{"error":{"status":$status}}}""")

        val openAi = client.chat(chatBody("x"))
        val anthropic = client.messages(messagesBody("x"))

        assertEquals(status, openAi.status.value())
        assertEquals(
            """{"error":{"message":"mock-provider injected HTTP $status.","type":"$openAiType","param":null,""" +
                """"code":${openAiCode?.let { "\"$it\"" } ?: "null"}}}""",
            openAi.bodyText(),
        )
        val requestId = anthropic.responseHeaders.getFirst("request-id")
        assertEquals(status, anthropic.status.value())
        assertEquals(
            """{"type":"error","error":{"type":"$anthropicType","message":"mock-provider injected HTTP $status."},""" +
                """"request_id":"$requestId"}""",
            anthropic.bodyText(),
        )
    }

    @ParameterizedTest
    @EnumSource(Api::class)
    fun `hang - a client that gives up after 500 ms is journaled as client_cancelled`(api: Api) {
        client.putScenario(api.model, """{"otherwise":{"fault":{"kind":"hang"}}}""")

        val failure =
            request(api, body(api, stream = false))
                .bodyToMono(String::class.java)
                .timeout(Duration.ofMillis(500))
                .map { "completed" }
                .onErrorResume { Mono.just(it.javaClass.simpleName) }
                .block(Duration.ofSeconds(5))

        assertEquals("TimeoutException", failure)
        val entry = awaitEntry(api.model) { it["outcome"]?.get("kind")?.stringValue() == "client_cancelled" }
        assertEquals("client_cancelled", entry["outcome"]?.get("kind")?.stringValue())
    }

    @Test
    fun `hang - held until mock max-hold, then 504 in the provider shape and timed_out`() {
        client.putScenario("fault-chat", """{"otherwise":{"fault":{"kind":"hang"}}}""")

        val response =
            client
                .mutate()
                .responseTimeout(
                    Duration.ofSeconds(10),
                ).build()
                .chat(chatBody("x", model = "fault-chat"))

        assertEquals(504, response.status.value())
        assertEquals("server_error", response.json<String>("$.error.type"))
        assertEquals("timed_out", awaitEntry("fault-chat") { true }["outcome"]?.get("kind")?.stringValue())
    }

    @ParameterizedTest
    @EnumSource(Api::class)
    fun `stall - three pieces, then silence until the client gives up`(api: Api) {
        client.putScenario(api.model, """{"otherwise":{"fault":{"kind":"stall","afterPieces":3}}}""")

        val received = streamUntilSilent(api)

        assertEquals(3, pieces(api, received)) { received }
        val entry = awaitEntry(api.model) { it["outcome"]?.get("kind")?.stringValue() == "client_cancelled" }
        assertEquals(3, entry["outcome"]?.get("piecesSent")?.intValue())
    }

    @ParameterizedTest
    @EnumSource(Api::class)
    fun `disconnect in a stream - three pieces, then a premature close, journaled as aborted`(api: Api) {
        client.putScenario(api.model, """{"otherwise":{"fault":{"kind":"disconnect","afterPieces":3}}}""")
        val received = Collections.synchronizedList(mutableListOf<String>())

        val error =
            request(api, body(api, stream = true))
                .bodyToFlux(DataBuffer::class.java)
                .map { buffer -> buffer.toString(Charsets.UTF_8).also { DataBufferUtils.release(buffer) } }
                .doOnNext { received += it }
                .then(Mono.just("completed"))
                .onErrorResume { failure -> Mono.just(causes(failure)) }
                .block(Duration.ofSeconds(5))

        assertTrue("PrematureCloseException" in error.orEmpty()) { error }
        assertEquals(3, pieces(api, received.joinToString(""))) { received.toString() }
        val entry = awaitEntry(api.model) { true }
        assertEquals("aborted", entry["outcome"]?.get("kind")?.stringValue())
        assertEquals(3, entry["outcome"]?.get("piecesSent")?.intValue())
    }

    @ParameterizedTest
    @EnumSource(Api::class)
    fun `disconnect without stream - 200 and headers, part of the body, then the connection closes`(api: Api) {
        client.putScenario(api.model, """{"otherwise":{"fault":{"kind":"disconnect"}}}""")
        val payload = body(api, stream = false)
        val auth = if (api == Api.OPENAI) "Authorization: Bearer $KEY" else "x-api-key: $KEY"

        val raw =
            Socket("localhost", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val request =
                    "POST ${api.path} HTTP/1.1\r\nHost: localhost\r\n$auth\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${payload.encodeToByteArray().size}\r\n\r\n$payload"
                socket.getOutputStream().write(request.encodeToByteArray())
                socket.getInputStream().readAllBytes().decodeToString()
            }

        assertTrue(raw.startsWith("HTTP/1.1 200")) { raw }
        assertTrue("""{"id":"""" in raw) { raw }
        // A complete chunked body ends with the zero-length chunk; an aborted one does not.
        assertFalse(raw.endsWith("0\r\n\r\n")) { raw }
        assertEquals("aborted", awaitEntry(api.model) { true }["outcome"]?.get("kind")?.stringValue())
    }

    @Test
    fun `stream_error - OpenAI sends a data event with the error envelope and no done sentinel`() {
        client.putScenario("fault-chat", """{"otherwise":{"fault":{"kind":"stream_error","afterPieces":2}}}""")

        val text = client.chat(chatBody("x", model = "fault-chat", extra = ""","stream":true""")).bodyText()

        assertTrue(
            text.endsWith(
                """data: {"error":{"message":"mock-provider injected HTTP 500.","type":"server_error",""" +
                    """"param":null,"code":null}}""" +
                    "\n\n",
            ),
        ) { text }
        assertFalse("[DONE]" in text)
        assertEquals(2, pieces(Api.OPENAI, text))
        assertEquals("stream_error", awaitEntry("fault-chat") { true }["outcome"]?.get("kind")?.stringValue())
    }

    @Test
    fun `stream_error - Anthropic sends an error event, overloaded by default, or the step's error`() {
        client.putScenario(
            "fault-claude",
            """{"steps":[{"fault":{"kind":"stream_error"}},
            {"fault":{"kind":"stream_error"},"error":{"status":429,"message":"slow down"}}]}""",
        )

        val first = client.messages(messagesBody("x", model = "fault-claude", extra = ""","stream":true""")).bodyText()
        val second = client.messages(messagesBody("x", model = "fault-claude", extra = ""","stream":true""")).bodyText()

        assertTrue(
            first.endsWith(
                "event: error\ndata: " +
                    """{"type":"error","error":{"type":"overloaded_error",""" +
                    """"message":"mock-provider injected HTTP 529."}}""" +
                    "\n\n",
            ),
        ) { first }
        assertTrue(
            second.endsWith("""{"type":"error","error":{"type":"rate_limit_error","message":"slow down"}}""" + "\n\n"),
        ) { second }
        assertFalse("message_stop" in first)
    }

    @Test
    fun `stream_error without stream is an HTTP error`() {
        client.putScenario("fault-chat", """{"otherwise":{"fault":{"kind":"stream_error"},"error":{"status":502}}}""")

        assertEquals(502, client.chat(chatBody("x", model = "fault-chat")).status.value())
    }

    @ParameterizedTest
    @EnumSource(Api::class)
    fun `malformed - non-stream body is not JSON, a stream has one bad data event and the normal ending`(api: Api) {
        client.putScenario(api.model, """{"otherwise":{"fault":{"kind":"malformed","afterPieces":1}}}""")

        val plain = if (api == Api.OPENAI) client.chat(body(api, false)) else client.messages(body(api, false))
        val stream = if (api == Api.OPENAI) client.chat(body(api, true)) else client.messages(body(api, true))
        val bad = stream.bodyText().split("\n\n").filter { "{not json" in it }

        assertEquals(200, plain.status.value())
        assertNull(runCatching { MAPPER.readTree(plain.bodyText()) }.getOrNull())
        assertEquals(1, bad.size) { stream.bodyText() }
        val ending =
            if (api ==
                Api.OPENAI
            ) {
                "data: [DONE]\n\n"
            } else {
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            }
        assertTrue(stream.bodyText().endsWith(ending)) { stream.bodyText() }
        assertEquals(1, pieces(api, stream.bodyText()))
    }

    private fun request(
        api: Api,
        body: String,
    ): WebClient.ResponseSpec = spec(api, body).retrieve()

    private fun spec(
        api: Api,
        body: String,
    ): WebClient.RequestHeadersSpec<*> =
        WebClient
            .create("http://localhost:$port")
            .post()
            .uri(api.path)
            .header("authorization", "Bearer $KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)

    private fun body(
        api: Api,
        stream: Boolean,
    ): String =
        when (api) {
            Api.OPENAI -> chatBody("fault", model = api.model, extra = ""","stream":$stream""")
            Api.ANTHROPIC -> messagesBody("fault", model = api.model, extra = ""","stream":$stream""")
        }

    // Reads until the server is silent for 800 ms, then gives up (cancels), like a client with an idle timeout.
    private fun streamUntilSilent(api: Api): String {
        val received = Collections.synchronizedList(mutableListOf<String>())
        request(api, body(api, stream = true))
            .bodyToFlux(DataBuffer::class.java)
            .map { buffer -> buffer.toString(Charsets.UTF_8).also { DataBufferUtils.release(buffer) } }
            .doOnNext { received += it }
            .timeout(Duration.ofMillis(800))
            .onErrorResume { Flux.empty() }
            .blockLast(Duration.ofSeconds(5))
        return received.joinToString("")
    }

    private fun pieces(
        api: Api,
        text: String,
    ): Int =
        when (api) {
            Api.OPENAI -> Regex(""""delta":\{"content":"[^"]""").findAll(text).count()
            Api.ANTHROPIC -> Regex(""""text_delta","text":"""").findAll(text).count()
        }

    // Polls the journal: an entry is written when the request ends, which a cancelled client does not wait for.
    private fun awaitEntry(
        model: String,
        accept: (Map<String, JsonNode>) -> Boolean,
    ): Map<String, JsonNode> {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (true) {
            val entries = MAPPER.readTree(client.getJson("/__mock/requests?model=$model")).get("data").values()
            val match = entries.map { node -> node.properties().associate { it.key to it.value } }.lastOrNull(accept)
            if (match != null) return match
            check(System.nanoTime() < deadline) { "no matching journal entry for $model: $entries" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun causes(failure: Throwable): String =
        generateSequence(failure) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName }

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
        const val SOCKET_TIMEOUT_MS = 5000
        const val POLL_MS = 50L
    }
}
