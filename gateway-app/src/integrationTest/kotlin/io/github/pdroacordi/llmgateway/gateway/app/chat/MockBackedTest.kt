package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.Socket
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/**
 * The real adapter against the shared mock over real HTTP. No test here reaches a real provider. Bodies are bytes, as
 * Spring logs a String body at TRACE, and these clients share the log NoLeakIntegrationTest searches.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
open class MockBackedTest {
    @LocalServerPort
    protected var port: Int = 0

    protected val mapper: JsonMapper = JsonMapper.builder().build()

    /** [mapper], for helpers outside a subclass. */
    val json: JsonMapper get() = mapper

    protected val gateway: WebTestClient by lazy { client("http://127.0.0.1:$port") }

    private val mock: WebTestClient by lazy { client(SharedMockProvider.baseUrl) }

    private fun client(url: String): WebTestClient =
        WebTestClient
            .bindToServer()
            .baseUrl(url)
            .responseTimeout(Duration.ofSeconds(30))
            .build()

    @BeforeEach
    fun resetMock() {
        SharedMockProvider.reset()
    }

    protected fun scenario(
        json: String,
        model: String = MODEL,
    ) {
        mock
            .put()
            .uri("/__mock/scenarios/{model}", model)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(json.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isNoContent
    }

    /** Journal entries of the request with this gateway ID, oldest first. */
    protected fun journalOf(requestId: String): List<JsonNode> =
        read("/__mock/requests?header=x-client-request-id:$requestId").get("data").values().toList()

    protected fun journal(): List<JsonNode> = read("/__mock/requests").get("data").values().toList()

    protected fun lastSeq(): Long = read("/__mock/stats").get("lastSeq").longValue()

    protected fun eventually(condition: () -> Boolean) {
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(25)).until(condition)
    }

    protected fun chat(
        body: String = MINIMAL,
        headers: Map<String, String> = emptyMap(),
    ): WebTestClient.ResponseSpec =
        gateway
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { target -> headers.forEach { (name, value) -> target.set(name, value) } }
            .bodyValue(body.encodeToByteArray())
            .exchange()

    /** One stream over a raw socket, to its end: a test sees what the wire holds, chunked framing included. */
    protected fun rawStream(body: String = STREAM): RawStream =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(rawRequest(body, "Connection: close\r\n").encodeToByteArray())
            RawStream.of(socket.getInputStream().readAllBytes())
        }

    protected fun rawRequest(
        body: String = STREAM,
        headers: String = "",
    ): String =
        "POST $ROUTE HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n$headers" +
            "Content-Length: ${body.encodeToByteArray().size}\r\n\r\n$body"

    protected fun fingerprint(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())).take(8)

    private fun read(path: String): JsonNode {
        val body =
            mock
                .get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody
        return mapper.readTree(checkNotNull(body))
    }

    /** With `include_usage` set to [includeUsage]. */
    protected fun asking(includeUsage: Boolean = true): String =
        STREAM.dropLast(1) + ""","stream_options":{"include_usage":$includeUsage}}"""

    companion object {
        const val ROUTE = "/v1/chat/completions"
        const val MODEL = "mock-chat"
        const val MINIMAL = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}"""
        const val STREAM = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        const val SOCKET_TIMEOUT_MS = 10_000

        // NoLeakIntegrationTest's canary. Set here: Spring calls the superclass's @DynamicPropertySource last, so a
        // subclass cannot override it (docs/knowledge/gotchas/dynamic-property-source-superclass-registers-last.md).
        const val KEY = "sk-canary-key"
        const val ANTHROPIC_KEY = "sk-ant-canary-key"

        @JvmStatic
        @DynamicPropertySource
        fun mockProvider(registry: DynamicPropertyRegistry) {
            registry.add("llmgw.providers.openai.base-url") { "${SharedMockProvider.baseUrl}/v1" }
            registry.add("llmgw.providers.openai.api-key") { KEY }
            registry.add("llmgw.providers.anthropic.base-url") { "${SharedMockProvider.baseUrl}/v1" }
            registry.add("llmgw.providers.anthropic.api-key") { ANTHROPIC_KEY }
        }
    }
}

/** [frames] hold each event without its blank line; [cleanEnd] says the body ended in the zero-length chunk. */
class RawStream(
    private val head: String,
    val body: String,
    val cleanEnd: Boolean,
) {
    val status: Int = head.substringAfter(' ').substringBefore(' ').toInt()
    val frames: List<String> = body.split("\n\n").filter { it.isNotEmpty() }

    fun header(name: String): String? =
        head
            .lines()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    val requestId: String get() = checkNotNull(header("x-request-id"))

    companion object {
        private const val SEPARATOR = "\r\n\r\n"

        fun of(bytes: ByteArray): RawStream {
            val text = bytes.toString(Charsets.ISO_8859_1)
            val start = text.indexOf(SEPARATOR) + SEPARATOR.length
            var position = start
            val body = StringBuilder()
            var clean = false
            while (position < text.length && !clean) {
                val lineEnd = text.indexOf("\r\n", position)
                if (lineEnd < 0) break
                val size = text.substring(position, lineEnd).toInt(radix = 16)
                clean = size == 0
                body.append(text, lineEnd + 2, minOf(lineEnd + 2 + size, text.length))
                position = lineEnd + 2 + size + 2
            }
            val decoded = body.toString().toByteArray(Charsets.ISO_8859_1).decodeToString()
            return RawStream(text.substring(0, start), decoded, clean && text.endsWith("0$SEPARATOR"))
        }
    }
}
