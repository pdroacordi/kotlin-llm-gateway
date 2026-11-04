package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.json.JsonMapper
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A real socket: the pre-commit path runs through Spring's ChannelSendOperator (ADR-GW-02-001). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class)
@ExtendWith(OutputCaptureExtension::class)
class ChatStreamRouteTest(
    @Autowired private val provider: FakeChatModelPort,
    @LocalServerPort private val port: Int,
) {
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val mapper = JsonMapper.builder().build()

    @BeforeEach
    fun resetProvider() {
        provider.reset()
    }

    private fun post(
        body: String = STREAM,
        query: String = "",
        accept: String? = null,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$ROUTE$query"))
                .header("Content-Type", "application/json")
                .apply { accept?.let { header("Accept", it) } }
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun withOptions(options: String): String = STREAM.dropLast(1) + ""","stream_options":$options}"""

    private fun HttpResponse<String>.header(name: String): String? = headers().firstValue(name).orElse(null)

    private fun assertNoStreamHeader(response: HttpResponse<String>) {
        assertEquals("application/json", response.header("content-type"))
        assertNull(response.header("cache-control"))
        assertNull(response.header("x-accel-buffering"))
        assertNull(response.header("x-gateway-deployment"))
    }

    @ParameterizedTest
    @MethodSource("failures")
    fun `Failed first gives the row's status and envelope with no stream header left`(failure: ProviderFailure) {
        provider.streamAnswer = { flowOf(ProviderStreamEvent.Failed(failure)) }

        val response = post()

        val requestId = RequestId(Uuid.parse(checkNotNull(response.header("x-request-id"))))
        val reply = failure.toReply(requestId)
        assertEquals(reply.status, response.statusCode())
        assertNoStreamHeader(response)
        reply.headers.forEach { (name, value) -> assertEquals(value, response.header(name)) }
        val error = mapper.readTree(response.body()).get("error")
        assertEquals(reply.message, error.get("message").stringValue())
        assertEquals(reply.code, error.get("code").stringValue())
        assertEquals(setOf("message", "type", "param", "code"), error.propertyNames().toSet())
    }

    @Test
    fun `a thrown bug first gives 500 internal_error, with no stream header left`() {
        provider.streamAnswer = { flow { error("a bug") } }

        val response = post()

        assertEquals(500, response.statusCode())
        assertNoStreamHeader(response)
        assertEquals("internal_error", mapper.readTree(response.body()).at("/error/code").stringValue())
    }

    @Test
    fun `a stream gives 200 with the commit headers and no length`() {
        val response = post()

        assertEquals(200, response.statusCode())
        assertEquals("text/event-stream;charset=UTF-8", response.header("content-type"))
        assertEquals("no-cache", response.header("cache-control"))
        assertEquals("no", response.header("x-accel-buffering"))
        assertEquals("openai", response.header("x-gateway-deployment"))
        assertEquals("chunked", response.header("transfer-encoding"))
        assertEquals('7', checkNotNull(response.header("x-request-id"))[VERSION_INDEX])
        assertNull(response.header("content-length"))
        assertNull(response.header("x-gateway-usage-estimated"))
    }

    @Test
    fun `the frames are chunks, then DONE, and a client that did not ask sees no usage`() {
        val frames = post().body().split("\n\n").filter { it.isNotEmpty() }

        assertEquals("data: [DONE]", frames.last())
        assertEquals(3, frames.size)
        assertFalse(frames.any { "usage" in it }) { frames.toString() }
    }

    @Test
    fun `a client that asked sees usage null on each chunk and the usage chunk last`() {
        val frames =
            post(withOptions("""{"include_usage":true}"""))
                .body()
                .split("\n\n")
                .filter { it.isNotEmpty() }

        assertTrue(""""usage":null""" in frames.first()) { frames.first() }
        assertTrue(""""prompt_tokens":14""" in frames[frames.size - 2]) { frames.toString() }
    }

    @Test
    fun `a stream that ends in DONE with an unasked usage still commits nothing before the first chunk`() {
        provider.streamAnswer = { flowOf(FakeChatModelPort.USAGE_CHUNK, ProviderStreamEvent.Completed) }

        val response = post()

        assertEquals(502, response.statusCode())
        assertNoStreamHeader(response)
    }

    @ParameterizedTest
    @ValueSource(strings = ["application/json", "text/plain", "text/event-stream"])
    fun `Accept is ignored`(accept: String) {
        val response = post(accept = accept)

        assertEquals(200, response.statusCode())
        assertEquals("text/event-stream;charset=UTF-8", response.header("content-type"))
    }

    @ParameterizedTest
    @MethodSource("rejections")
    fun `each stream_options row gives its envelope and reaches no port`(
        body: String,
        error: GatewayError,
    ) {
        val response = post(body)

        val requestId = RequestId(Uuid.parse(checkNotNull(response.header("x-request-id"))))
        val reply = error.toReply(requestId)
        assertEquals(400, response.statusCode())
        assertEquals(reply.message, mapper.readTree(response.body()).at("/error/message").stringValue())
        assertEquals(reply.param, mapper.readTree(response.body()).at("/error/param").stringValue())
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `the port gets the options the client sent`() {
        post(withOptions("""{"include_usage":false,"include_obfuscation":true}"""))

        val options =
            checkNotNull(
                provider.calls
                    .single()
                    .request.streamOptions,
            )
        assertFalse(options.includeUsage)
        assertEquals(setOf("include_obfuscation"), options.extras.members.keys)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"include_usage":null}""", """{"include_usage":null,"include_obfuscation":true}"""])
    fun `include_usage null counts as absent`(options: String) {
        val response = post(withOptions(options))

        assertEquals(200, response.statusCode())
        assertFalse("usage" in response.body()) { response.body() }
    }

    @Test
    fun `include_usage null without stream is still the 400 of a stream_options without stream`() {
        val response = post(STREAM.replace(""""stream":true""", """"stream_options":{"include_usage":null}"""))

        assertEquals(400, response.statusCode())
    }

    @Test
    fun `stream_options null counts as absent`() {
        val response = post(withOptions("null"))

        assertEquals(200, response.statusCode())
        assertNull(
            provider.calls
                .single()
                .request.streamOptions,
        )
    }

    @Test
    fun `a bug after the first frame gives an internal_error frame, a clean end and one ERROR line`(
        output: CapturedOutput,
    ) {
        provider.streamAnswer =
            {
                flow {
                    emit(FakeChatModelPort.chunk("Hi"))
                    error("a bug in the flow")
                }
            }

        val raw = rawPost("?$QUERY")

        assertTrue(raw.startsWith("HTTP/1.1 200")) { raw }
        assertTrue(""""code":"internal_error"""" in raw) { raw }
        assertFalse("[DONE]" in raw) { raw }
        assertTrue(raw.endsWith("0\r\n\r\n")) { raw }
        await().atMost(WAIT).until { "Stream crashed after commit" in output.all }
        assertTrue("a bug in the flow" in output.all) { output.all }
        assertFalse(QUERY in output.all) { output.all }
        assertFalse("Error finishing response" in output.all) { output.all }
        val requestId = requestIdOf(raw)
        assertEquals(1, output.all.lines().count { " ERROR " in it && requestId in it }) { output.all }
    }

    @Test
    fun `an expected failure after the first frame leaves one ERROR line, from the stream log`(
        output: CapturedOutput,
    ) {
        provider.streamAnswer =
            {
                flowOf(FakeChatModelPort.chunk("Hi"), ProviderStreamEvent.Failed(ProviderFailure.UpstreamError))
            }

        val raw = rawPost("")

        assertTrue(""""code":"upstream_error"""" in raw) { raw }
        assertTrue(raw.endsWith("0\r\n\r\n")) { raw }
        await().atMost(WAIT).until { "Stream failed" in output.all }
        val requestId = requestIdOf(raw)
        val line = output.all.lines().single { " ERROR " in it && requestId in it }
        assertTrue("Stream failed" in line && requestId in line && "code=upstream_error" in line) { line }
    }

    private fun requestIdOf(raw: String): String =
        checkNotNull(Regex("(?i)x-request-id: (\\S+)").find(raw)).groupValues[1]

    private fun rawPost(query: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request =
                "POST $ROUTE$query HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${STREAM.length}\r\nConnection: close\r\n\r\n$STREAM"
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getInputStream().readAllBytes().decodeToString()
        }

    private companion object {
        const val ROUTE = "/v1/chat/completions"
        const val STREAM = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        const val USER = """{"role":"user","content":"Hi"}"""
        const val QUERY = "secret-query=abc123"
        const val VERSION_INDEX = 14
        const val SOCKET_TIMEOUT_MS = 5000
        val WAIT: Duration = Duration.ofSeconds(5)

        @JvmStatic
        fun failures(): List<ProviderFailure> =
            listOf(
                ProviderFailure.ModelNotFound,
                ProviderFailure.RejectedByProvider(400, "context_length_exceeded", "messages[0].content"),
                ProviderFailure.RejectedByProvider(422, null, null),
                ProviderFailure.UpstreamAuthFailed,
                ProviderFailure.UpstreamRateLimited(7.seconds),
                ProviderFailure.UpstreamRateLimited(null),
                ProviderFailure.UpstreamQuotaExhausted,
                ProviderFailure.ProviderUnavailable(7.seconds),
                ProviderFailure.ProviderUnavailable(null),
                ProviderFailure.UpstreamTimeout,
                ProviderFailure.UpstreamError,
            )

        @JvmStatic
        fun rejections(): List<Arguments> {
            val errors = GatewayError.InvalidRequest
            val base = """"model":"m","messages":[$USER]"""
            return listOf(
                Arguments.of("""{$base,"stream":true,"stream_options":5}""", errors.streamOptionsMistyped),
                Arguments.of(
                    """{$base,"stream":true,"stream_options":{"include_usage":"yes"}}""",
                    errors.includeUsageMistyped,
                ),
                Arguments.of("""{$base,"stream_options":{}}""", errors.streamOptionsNeedStream),
                Arguments.of("""{$base,"stream":false,"stream_options":{}}""", errors.streamOptionsNeedStream),
            )
        }
    }
}
