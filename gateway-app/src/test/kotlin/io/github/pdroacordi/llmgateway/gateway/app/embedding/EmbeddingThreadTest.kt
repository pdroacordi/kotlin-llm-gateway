package io.github.pdroacordi.llmgateway.gateway.app.embedding

import com.sun.net.httpserver.HttpServer
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.node.IntNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.NumericNode
import tools.jackson.databind.node.ValueNode
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The real adapter against an in-JVM provider, so no Docker (ADR-GW-05-010). [Marks] sees the thread of the one call
 * the reader makes for [MARK], reading the extra as an int, and of the one the writer makes, as a BigInteger.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EmbeddingThreadTest.Wiring::class)
@Timeout(60)
// A worst-case request is priced at this limit: at the default it would pass a test JVM's budget of a quarter heap.
@TestPropertySource(properties = ["llmgw.providers.openai.embeddings.max-response-size=1MB"])
class EmbeddingThreadTest(
    @LocalServerPort private val port: Int,
) {
    class Marks : JsonNodeFactory() {
        @Volatile
        var reader: Thread? = null

        @Volatile
        var writer: Thread? = null

        @Volatile
        var requestParse: Thread? = null

        @Volatile
        var requestTyping: Thread? = null

        @Volatile
        var requestWrite: Thread? = null

        override fun numberNode(value: Int): NumericNode {
            if (value == MARK) reader = Thread.currentThread()
            if (value == REQUEST_MARK) {
                requestParse = Thread.currentThread()
                return TypedOn(this)
            }
            return super.numberNode(value)
        }

        override fun numberNode(value: Long): NumericNode {
            if (value == REQUEST_MARK.toLong()) requestWrite = Thread.currentThread()
            return super.numberNode(value)
        }

        override fun numberNode(value: BigInteger?): ValueNode {
            if (value == BigInteger.valueOf(MARK.toLong())) writer = Thread.currentThread()
            return super.numberNode(value)
        }
    }

    /** The typing of a token ID reads it with `longValue`, and nothing else does. */
    class TypedOn(
        private val marks: Marks,
    ) : IntNode(REQUEST_MARK) {
        override fun longValue(): Long {
            marks.requestTyping = Thread.currentThread()
            return super.longValue()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        fun marksCustomizer(): JsonMapperBuilderCustomizer = JsonMapperBuilderCustomizer { it.nodeFactory(MARKS) }
    }

    private fun embed(body: String = """{"model":"m","input":"a"}"""): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/v1/embeddings"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `the reader runs off the event loops, and the handler's write follows it there`() {
        MARKS.reader = null
        MARKS.writer = null

        assertEquals(200, embed().statusCode())

        val reader = checkNotNull(MARKS.reader) { "the reader did not read the extra" }
        assertFalse(reader.name.startsWith("reactor-http-")) { reader.name }
        assertFalse(reader.name.startsWith("webflux-http-")) { reader.name }
        assertTrue(reader.name.startsWith(LANE)) { reader.name }
        assertSame(reader, MARKS.writer) { "writer on ${MARKS.writer?.name}, reader on ${reader.name}" }
    }

    @Test
    fun `a large token-array request is parsed, typed and written on the lane, off the event loops`() {
        MARKS.requestParse = null
        MARKS.requestTyping = null
        MARKS.requestWrite = null
        val input = List(LARGE_REQUEST_ITEMS) { "[$REQUEST_MARK]" }.joinToString(",")

        assertEquals(200, embed("""{"model":"m","input":[$input]}""").statusCode())

        val stages =
            mapOf(
                "parse" to MARKS.requestParse,
                "typing" to MARKS.requestTyping,
                "write" to MARKS.requestWrite,
            ).mapValues { checkNotNull(it.value) { "${it.key} did not run" }.name }
        assertTrue(stages.values.all { it.startsWith(LANE) }) { stages.toString() }
    }

    @Test
    fun `a float reply reaches the client with each literal and the array's whitespace unchanged`() {
        val body = embed().body()

        assertTrue(""""embedding":[ 1e-5 ,${"\n"}  0.10,1E+2 ]""" in body) { body }
    }

    @Test
    fun `an unknown member at the top, in an item and in usage reaches the client verbatim`() {
        val body = embed().body()

        assertTrue(""""x_item":{"a":[1,null]}""" in body) { body }
        assertTrue(""""x_usage":"u"""" in body) { body }
        assertTrue(""""x_mark":424243""" in body) { body }
    }

    @Test
    fun `a provider's completion_tokens does not reach the client`() {
        val body = embed().body()

        assertFalse("completion_tokens" in body) { body }
        assertTrue(""""usage":{"prompt_tokens":1,"total_tokens":1,"x_usage":"u"}""" in body) { body }
    }

    companion object {
        private const val MARK = 424_243
        private const val REQUEST_MARK = 424_244
        private const val LARGE_REQUEST_ITEMS = 100_000
        private const val LANE = "embeddings-openai-cpu-"
        private val MARKS = Marks()
        private const val REPLY =
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[ 1e-5 ,
  0.10,1E+2 ],"x_item":{"a":[1,null]}}],"model":"m",
  "usage":{"prompt_tokens":1,"completion_tokens":5,"total_tokens":6,"x_usage":"u"},"x_mark":424243}"""

        private val provider: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/v1/embeddings") { exchange ->
                    exchange.requestBody.readAllBytes()
                    val bytes = REPLY.encodeToByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun inJvmProvider(registry: DynamicPropertyRegistry) {
            registry.add("llmgw.providers.openai.base-url") { "http://127.0.0.1:${provider.address.port}/v1" }
        }

        @JvmStatic
        @AfterAll
        fun stopProvider() {
            provider.stop(0)
        }
    }
}
