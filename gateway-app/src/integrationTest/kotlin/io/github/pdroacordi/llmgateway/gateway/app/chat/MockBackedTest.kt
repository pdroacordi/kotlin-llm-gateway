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

    companion object {
        const val ROUTE = "/v1/chat/completions"
        const val MODEL = "mock-chat"
        const val MINIMAL = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}"""

        // NoLeakIntegrationTest's canary. Set here: Spring calls the superclass's @DynamicPropertySource last, so a
        // subclass cannot override it (docs/knowledge/gotchas/dynamic-property-source-superclass-registers-last.md).
        const val KEY = "sk-canary-key"

        @JvmStatic
        @DynamicPropertySource
        fun mockProvider(registry: DynamicPropertyRegistry) {
            registry.add("llmgw.providers.openai.base-url") { "${SharedMockProvider.baseUrl}/v1" }
            registry.add("llmgw.providers.openai.api-key") { KEY }
        }
    }
}
