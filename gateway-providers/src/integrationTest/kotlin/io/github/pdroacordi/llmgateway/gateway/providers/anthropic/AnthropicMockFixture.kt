package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamConfig
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

/** The adapter and the mock's admin API, for this package's integration tests. */
internal class AnthropicMockFixture : AutoCloseable {
    private val mapper = JsonMapper.builder().build()
    private val http = HttpClient.newHttpClient()
    private val adapters = mutableListOf<AnthropicChatAdapter>()

    fun adapter(
        baseUrl: String = "${SharedMockProvider.baseUrl}/v1",
        requestTimeout: Duration = Duration.ofSeconds(10),
        stream: StreamConfig = STREAM,
        maxResponseBytes: Int = 1_048_576,
        pool: PoolConfig = POOL,
    ): AnthropicChatAdapter =
        AnthropicChatAdapter(
            AnthropicAdapterConfig(
                baseUrl = URI.create(baseUrl),
                apiKey = ApiKey(KEY),
                version = "2023-06-01",
                defaultMaxTokens = 4096,
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = requestTimeout,
                maxResponseBytes = maxResponseBytes,
                pool = pool,
                stream = stream,
            ),
            mapper,
            Clock.systemUTC(),
        ).also { adapters += it }

    fun scenario(json: String) {
        val request =
            HttpRequest
                .newBuilder(URI.create("${SharedMockProvider.baseUrl}/__mock/scenarios/$MODEL"))
                .header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == NO_CONTENT) { "PUT scenario: ${response.statusCode()} ${response.body()}" }
    }

    fun journal(): List<JsonNode> = admin("/__mock/requests").get("data").values().toList()

    fun outcome(): String? = journal().singleOrNull()?.at("/outcome/kind")?.stringValue()

    fun lastSeq(): Long = admin("/__mock/stats").get("lastSeq").longValue()

    private fun admin(path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("${SharedMockProvider.baseUrl}$path")).GET().build()
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
    }

    fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not true within 2 s; journal: ${journal()}" }
            Thread.sleep(POLL_MS)
        }
    }

    override fun close() {
        adapters.forEach { it.close() }
    }

    companion object {
        const val KEY = "provider-key-not-real"
        const val MODEL = "mock-claude"
        private const val NO_CONTENT = 204
        private const val POLL_MS = 25L
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val POOL = PoolConfig(16, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5))
        val STREAM = StreamConfig(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), 1_048_576)

        fun call(extras: JsonValue.Object = JsonValue.Object.EMPTY): ChatCall =
            ChatCall(
                REQUEST_ID,
                Deployment(DeploymentId("anthropic"), ProviderId("anthropic"), ModelName(MODEL)),
                ChatRequest(ModelName(MODEL), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))), extras),
            )
    }
}
