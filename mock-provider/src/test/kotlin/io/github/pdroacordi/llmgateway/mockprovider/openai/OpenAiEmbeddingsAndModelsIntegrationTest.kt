package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.postJson
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import io.github.pdroacordi.llmgateway.mockprovider.generation.Tokenizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class OpenAiEmbeddingsAndModelsIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `the same input gives the same unit vector with the default 1536 dimensions`() {
        val first = vector(client.postJson("/v1/embeddings", """{"model":"mock-embed","input":"reset my password"}"""))
        val second = vector(client.postJson("/v1/embeddings", """{"model":"mock-embed","input":"reset my password"}"""))

        assertEquals(1536, first.size)
        assertEquals(first, second)
        assertEquals(1.0, sqrt(first.sumOf { it * it }), 1e-6)
    }

    @Test
    fun `requested dimensions and usage over several inputs`() {
        val result =
            client.postJson(
                "/v1/embeddings",
                """{"model":"mock-embed","input":["Hello, world!","two words"],"dimensions":3}""",
            )

        assertEquals(3, result.json<List<Double>>("$.data[1].embedding").size)
        assertEquals(1, result.json<Int>("$.data[1].index"))
        val tokens = Tokenizer.count("Hello, world!") + Tokenizer.count("two words")
        assertEquals(tokens, result.json<Int>("$.usage.prompt_tokens"))
        assertEquals(tokens, result.json<Int>("$.usage.total_tokens"))
        assertEquals("list", result.json<String>("$.object"))
        assertEquals("mock-embed", result.json<String>("$.model"))
    }

    @Test
    fun `base64 decodes as little-endian float32 to the float values`() {
        val floats =
            vector(client.postJson("/v1/embeddings", """{"model":"mock-embed","input":"abc def","dimensions":64}"""))
        val encoded =
            client
                .postJson(
                    "/v1/embeddings",
                    """{"model":"mock-embed","input":"abc def","dimensions":64,"encoding_format":"base64"}""",
                ).json<String>("$.data[0].embedding")
        val decoded =
            ByteBuffer
                .wrap(Base64.getDecoder().decode(encoded))
                .order(ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer()
                .let { buffer ->
                    List(buffer.remaining()) { buffer.get().toDouble() }
                }

        assertEquals(64, decoded.size)
        floats.indices.forEach { assertEquals(floats[it], decoded[it], 1e-7) }
    }

    @Test
    fun `token arrays are accepted`() {
        val result =
            client.postJson(
                "/v1/embeddings",
                """{"model":"mock-embed","input":[[1,2,3],[4]],"dimensions":8}""",
            )

        assertEquals(200, result.status.value())
        assertEquals(4, result.json<Int>("$.usage.prompt_tokens"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"\"", "[]", "[\"a\",\"\"]"])
    fun `empty input is 400 naming input`(input: String) {
        val result = client.postJson("/v1/embeddings", """{"model":"mock-embed","input":$input}""")

        assertEquals(400, result.status.value())
        assertEquals("input", result.json<String>("$.error.param"))
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
    }

    @Test
    fun `models lists every configured model`() {
        val result =
            client
                .get()
                .uri("/v1/models")
                .header("authorization", "Bearer $KEY")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .returnResult()

        assertEquals("list", result.json<String>("$.object"))
        assertEquals(
            listOf("mock-chat", "mock-chat-mini", "mock-claude", "mock-embed"),
            result.json<List<String>>("$.data[*].id"),
        )
        assertEquals(listOf("model"), result.json<List<String>>("$.data[*].object").distinct())
        assertEquals(listOf("mock-provider"), result.json<List<String>>("$.data[*].owned_by").distinct())
        assertEquals(1, result.json<List<Long>>("$.data[*].created").distinct().size)
    }

    private fun vector(
        result: org.springframework.test.web.reactive.server.EntityExchangeResult<ByteArray>,
    ): List<Double> = result.json<List<Number>>("$.data[0].embedding").map { it.toDouble() }
}
