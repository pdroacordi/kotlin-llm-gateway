package io.github.pdroacordi.llmgateway.gateway.app.embedding

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.embeddings.EmbeddingCreateParams
import io.github.pdroacordi.llmgateway.gateway.app.chat.AnthropicBackedTest
import io.github.pdroacordi.llmgateway.gateway.app.chat.MockBackedTest
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private const val FIXED_MESSAGE = "The provider rejected the request; see the code and param members."

/** Bodies are bytes through a plain client: a reply here reaches 32 MiB, over WebTestClient's buffer. */
open class EmbeddingBackedTest : MockBackedTest() {
    private val http: HttpClient = HttpClient.newHttpClient()

    protected class Reply(
        private val response: HttpResponse<ByteArray>,
        json: tools.jackson.databind.json.JsonMapper,
    ) {
        val status: Int = response.statusCode()
        val bytes: ByteArray = response.body()
        val body: JsonNode = json.readTree(bytes)

        fun header(name: String): String? = response.headers().firstValue(name).orElse(null)

        val requestId: String get() = checkNotNull(header("x-request-id"))
    }

    protected fun embed(body: String): Reply = send("http://127.0.0.1:$port$EMBEDDINGS", TestKeys.BEARER, body)

    /** The same call straight at the mock, for what the gateway must not change. */
    protected fun direct(body: String): Reply = send("${SharedMockProvider.baseUrl}$EMBEDDINGS", "Bearer $KEY", body)

    private fun send(
        url: String,
        authorization: String,
        body: String,
    ): Reply {
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return Reply(http.send(request, HttpResponse.BodyHandlers.ofByteArray()), mapper)
    }

    protected fun upstreamBody(requestId: String): String =
        journalOf(requestId)
            .single()
            .at("/body")
            .stringValue()

    companion object {
        const val EMBEDDINGS = "/v1/embeddings"
        const val EMBED_MODEL = "mock-embed"

        /** 2048 one-token inputs at 3072 dimensions: 16 KiB of base64 each, 32 MiB in all. */
        val FULL_BATCH: String =
            """{"model":"$EMBED_MODEL","input":[${List(2048) { "[1]" }.joinToString(",")}],""" +
                """"dimensions":3072,"encoding_format":"base64"}"""
    }
}

class EmbeddingsEndToEndTest : EmbeddingBackedTest() {
    private val sdk: OpenAIClient by lazy {
        OpenAIOkHttpClient
            .builder()
            .baseUrl("http://127.0.0.1:$port/v1")
            .apiKey(TestKeys.TEAM_KEY)
            .maxRetries(0)
            .responseValidation(true)
            .timeout(Duration.ofSeconds(30))
            .build()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        value = [
            """"a"             | 1""",
            """["a","b","c"]   | 3""",
            """[1,2]           | 1""",
            """[[1],[2,3]]     | 2""",
        ],
    )
    fun `each input form gives one item per input, with the headers`(
        input: String,
        items: Int,
    ) {
        val reply = embed("""{"model":"$EMBED_MODEL","input":$input,"dimensions":4}""")

        assertEquals(200, reply.status) { reply.bytes.decodeToString() }
        assertEquals("list", reply.body.get("object").stringValue())
        assertEquals(items, reply.body.get("data").size())
        reply.body.get("data").values().forEachIndexed { index, item ->
            assertEquals("embedding", item.get("object").stringValue())
            assertEquals(index, item.get("index").intValue())
            assertEquals(4, item.get("embedding").size())
        }
        assertEquals(EMBED_MODEL, reply.body.get("model").stringValue())
        assertTrue(reply.body.at("/usage/prompt_tokens").longValue() > 0)
        assertEquals(reply.body.at("/usage/prompt_tokens"), reply.body.at("/usage/total_tokens"))
        assertFalse(reply.body.get("usage").has("completion_tokens"))
        assertEquals("openai", reply.header("x-gateway-deployment"))
        assertEquals(1, journalOf(reply.requestId).size)
    }

    @Test
    fun `base64 is byte-identical to the mock's`() {
        val request = """{"model":"$EMBED_MODEL","input":["a b","c"],"dimensions":8,"encoding_format":"base64"}"""

        val reply = embed(request)

        val expected =
            direct(request)
                .body
                .get("data")
                .values()
                .map { it.get("embedding").stringValue() }
        assertEquals(
            expected,
            reply.body
                .get("data")
                .values()
                .map { it.get("embedding").stringValue() },
        )
    }

    @Test
    fun `a float reply holds the mock's vector`() {
        val request = """{"model":"$EMBED_MODEL","input":"a b","dimensions":8}"""

        val reply = embed(request)

        assertEquals(direct(request).body.at("/data/0/embedding"), reply.body.at("/data/0/embedding"))
    }

    @Test
    fun `encoding_format, dimensions, user and an unknown member reach the provider, llmgw does not`() {
        val reply =
            embed(
                """{"model":"$EMBED_MODEL","input":"a","encoding_format":"float","dimensions":3,""" +
                    """"user":"u-1","x_future":{"a":[1,null]},"llmgw":{"thinking_blocks":[]}}""",
            )

        assertEquals(
            """{"model":"mock-embed","input":"a","encoding_format":"float","dimensions":3,""" +
                """"user":"u-1","x_future":{"a":[1,null]}}""",
            upstreamBody(reply.requestId),
        )
    }

    @Test
    fun `an absent encoding_format stays absent`() {
        val reply = embed("""{"model":"$EMBED_MODEL","input":"a"}""")

        assertFalse("encoding_format" in upstreamBody(reply.requestId))
    }

    @Test
    fun `the provider sees the configured key, never the team's`() {
        val reply = embed("""{"model":"$EMBED_MODEL","input":"a"}""")

        val entry = journalOf(reply.requestId).single()
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
    }

    @Test
    fun `omitted usage gives estimated counts, flagged in the header and the body`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""", EMBED_MODEL)

        val reply = embed("""{"model":"$EMBED_MODEL","input":"12345"}""")

        assertEquals(200, reply.status)
        assertEquals("true", reply.header("x-gateway-usage-estimated"))
        assertEquals(2, reply.body.at("/usage/prompt_tokens").intValue())
        assertEquals(2, reply.body.at("/usage/total_tokens").intValue())
        assertTrue(reply.body.at("/llmgw/usage_estimated").booleanValue())
    }

    @Test
    fun `a reply with usage has neither flag`() {
        val reply = embed("""{"model":"$EMBED_MODEL","input":"a"}""")

        assertNull(reply.header("x-gateway-usage-estimated"))
        assertFalse(reply.body.has("llmgw"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"input":"a"}""",
            """{"model":"","input":"a"}""",
            """{"model":5,"input":"a"}""",
            """{"model":"mock-embed"}""",
            """{"model":"mock-embed","input":null}""",
            """{"model":"mock-embed","input":5}""",
            """{"model":"mock-embed","input":{}}""",
            """{"model":"mock-embed","input":["a",1]}""",
            """{"model":"mock-embed","input":[1.5]}""",
            """{"model":"mock-embed","input":[9223372036854775808]}""",
        ],
    )
    fun `a gateway row makes no upstream call`(body: String) {
        val before = lastSeq()

        val reply = embed(body)

        assertEquals(400, reply.status)
        assertEquals("invalid_request", reply.body.at("/error/code").stringValue())
        assertEquals(before, lastSeq())
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        quoteCharacter = '`',
        value = [
            """"input":""                      | input""",
            """"input":[]                      | input""",
            """"input":[""]                    | input""",
            """"input":[[]]                    | input""",
            """"input":"a","dimensions":0      | dimensions""",
            """"input":"a","dimensions":"x"    | dimensions""",
            """"input":"a","encoding_format":"x" | encoding_format""",
        ],
    )
    fun `a provider row is relayed as a 400 with its param and the fixed message`(
        members: String,
        param: String,
    ) {
        val reply = embed("""{"model":"$EMBED_MODEL",$members}""")

        assertEquals(400, reply.status) { reply.bytes.decodeToString() }
        assertEquals(param, reply.body.at("/error/param").stringValue())
        assertEquals(1, journalOf(reply.requestId).size)
        assertEquals(FIXED_MESSAGE, reply.body.at("/error/message").stringValue())
    }

    @Test
    fun `2049 items are the provider's to refuse`() {
        val reply = embed("""{"model":"$EMBED_MODEL","input":[${List(2049) { "\"a\"" }.joinToString(",")}]}""")

        assertEquals(400, reply.status)
        assertEquals("input", reply.body.at("/error/param").stringValue())
        assertEquals(1, journalOf(reply.requestId).size)
    }

    @Test
    fun `a relayed 400 keeps the provider's code and param`() {
        scenario(
            """{"otherwise":{"error":{"status":400,"code":"invalid_value","param":"dimensions","message":"m"}}}""",
            EMBED_MODEL,
        )

        val reply = embed("""{"model":"$EMBED_MODEL","input":"a"}""")

        assertEquals(400, reply.status)
        assertEquals("invalid_value", reply.body.at("/error/code").stringValue())
        assertEquals("dimensions", reply.body.at("/error/param").stringValue())
    }

    @Test
    fun `2048 inputs at 3072 dimensions in base64 give 200 at the default limit`() {
        val reply = embed(FULL_BATCH)

        assertEquals(200, reply.status)
        assertTrue(reply.bytes.size > 32 * 1024 * 1024) { reply.bytes.size.toString() }
        assertEquals(2048, reply.body.get("data").size())
        assertTrue(
            reply.body
                .get("data")
                .values()
                .all { it.get("embedding").stringValue().length == 16_384 },
        )
    }

    // A raw socket: closing it is a client that goes away, with no HTTP client to soften it.
    @Test
    fun `a client that disconnects during a hang leaves client_cancelled at the mock within 2 s`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""", EMBED_MODEL)
        val before = lastSeq()
        val body = """{"model":"$EMBED_MODEL","input":"a"}"""
        val request =
            "POST $EMBEDDINGS HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: application/json\r\n" +
                "Authorization: ${TestKeys.BEARER}\r\nContent-Length: ${body.length}\r\n\r\n$body"

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(request.encodeToByteArray())
                flush()
            }
            eventually { lastSeq() > before }
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }

    @Test
    fun `openai-java reads the floats the mock produced`() {
        val params =
            EmbeddingCreateParams
                .builder()
                .model(EMBED_MODEL)
                .input("a b")
                .dimensions(8L)
                .encodingFormat(EmbeddingCreateParams.EncodingFormat.FLOAT)
                .build()

        val response = sdk.embeddings().create(params)

        response.validate()
        val expected =
            direct("""{"model":"$EMBED_MODEL","input":"a b","dimensions":8}""")
                .body
                .at("/data/0/embedding")
                .values()
                .map { it.floatValue() }
        assertEquals(expected, response.data().single().embedding())
        assertEquals(EMBED_MODEL, response.model())
    }

    @Test
    fun `openai-java reads the same floats through its default encoding`() {
        val params =
            EmbeddingCreateParams
                .builder()
                .model(EMBED_MODEL)
                .input("a b")
                .dimensions(8L)
                .build()

        val response = sdk.embeddings().create(params)

        val expected =
            direct("""{"model":"$EMBED_MODEL","input":"a b","dimensions":8}""")
                .body
                .at("/data/0/embedding")
                .values()
                .map { it.floatValue() }
        assertEquals(expected, response.data().single().embedding())
    }
}

@TestPropertySource(properties = ["llmgw.providers.openai.embeddings.max-response-size=1MB"])
class EmbeddingLimitEndToEndTest : EmbeddingBackedTest() {
    @Test
    fun `at a lowered limit the same reply gives 502 upstream_error, not 413, with one journal entry`() {
        val reply = embed(FULL_BATCH)

        assertEquals(502, reply.status)
        assertEquals("upstream_error", reply.body.at("/error/code").stringValue())
        assertEquals(1, journalOf(reply.requestId).size)
    }
}

class AnthropicEmbeddingEndToEndTest : AnthropicBackedTest() {
    private fun post(model: String) =
        gateway
            .post()
            .uri(EmbeddingBackedTest.EMBEDDINGS)
            .header("Content-Type", "application/json")
            .bodyValue("""{"model":"$model","input":"a"}""".encodeToByteArray())
            .exchange()

    @Test
    fun `a model mapped to Anthropic gives 400 unsupported_value with no upstream call`() {
        val before = lastSeq()

        post(CLAUDE)
            .expectStatus()
            .isEqualTo(400)
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("unsupported_value")
            .jsonPath("$.error.param")
            .isEqualTo("model")

        assertEquals(before, lastSeq())
    }

    @Test
    fun `a model on OpenAI still answers with Anthropic enabled`() {
        post(EmbeddingBackedTest.EMBED_MODEL).expectStatus().isOk
    }
}
