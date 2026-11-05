package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.HexFormat
import kotlin.uuid.Uuid

// No retries in the adapter, so one call is one journal entry; every test can count on that.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OpenAiEmbeddingAdapterIntegrationTest {
    private val mapper = JsonMapper.builder().build()
    private val adapters = mutableListOf<OpenAiEmbeddingAdapter>()
    private val http = HttpClient.newHttpClient()

    private fun adapter(
        baseUrl: String = "${SharedMockProvider.baseUrl}/v1",
        requestTimeout: Duration = Duration.ofSeconds(10),
        maxResponseBytes: Int = 1_048_576,
    ): OpenAiEmbeddingAdapter =
        OpenAiEmbeddingAdapter(
            OpenAiEmbeddingAdapterConfig(
                baseUrl = URI.create(baseUrl),
                apiKey = ApiKey(KEY),
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = requestTimeout,
                maxResponseBytes = maxResponseBytes,
                pool =
                    PoolConfig(
                        maxConnections = 16,
                        pendingAcquireTimeout = Duration.ofSeconds(5),
                        maxIdleTime = Duration.ofSeconds(30),
                        maxLifeTime = Duration.ofMinutes(5),
                    ),
                cpuThreads = 2,
                cpuQueueSize = 64,
            ),
            mapper,
            Clock.systemUTC(),
        ).also { adapters += it }

    @BeforeEach
    fun resetMock() {
        SharedMockProvider.reset()
    }

    @AfterEach
    fun closeAdapters() {
        adapters.forEach { it.close() }
    }

    // First in the class, so the first request in this JVM is the one BlockHound sees.
    @Test
    @Order(1)
    fun `200 calls pass BlockHound on a non-blocking thread, the first one included`() {
        val adapter = adapter()
        adapter.warmUp()

        val results =
            Flux
                .range(0, CALLS)
                .flatMap({ onNonBlockingThread(adapter) }, CONCURRENCY)
                .collectList()
                .block()
                .orEmpty()

        assertEquals(CALLS, results.size)
        assertTrue(results.all { it is EmbeddingResult.Embedded }) { results.toString() }
    }

    // Spring starts coroutine handlers Unconfined, so the adapter runs on the calling thread, as here.
    @Suppress("InjectDispatcher")
    private fun onNonBlockingThread(adapter: OpenAiEmbeddingAdapter) =
        mono(Dispatchers.Unconfined) { adapter.embed(call()) }.subscribeOn(Schedulers.parallel())

    @Test
    fun `a success is read, with usage`() {
        val result = embed() as EmbeddingResult.Embedded

        assertEquals(ModelName(MODEL), result.list.model)
        assertEquals(
            0,
            result.list.data
                .single()
                .index,
        )
        assertEquals(
            '[',
            result.list.data
                .single()
                .vector.bytes
                .first()
                .toInt()
                .toChar(),
        )
        val usage = checkNotNull(result.usage)
        assertFalse(usage.estimated)
        assertTrue(usage.inputTokens.value > 0) { usage.toString() }
    }

    @Test
    fun `base64 comes back as a string`() {
        val extras = JsonValue.Object(mapOf("encoding_format" to JsonValue.Text("base64")))

        val result = embed(call(extras = extras)) as EmbeddingResult.Embedded

        assertEquals(
            '"',
            result.list.data
                .single()
                .vector.bytes
                .first()
                .toInt()
                .toChar(),
        )
    }

    @Test
    fun `the provider sees the configured key, the request ID and the body, and nothing else from the client`() {
        val extras =
            JsonValue.Object(
                linkedMapOf(
                    "x_future" to JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null)),
                    "dimensions" to JsonValue.Number("3"),
                    "user" to JsonValue.Text("u-1"),
                    "llmgw" to JsonValue.Object.EMPTY,
                ),
            )

        embed(call(extras = extras))

        val entry = journal().single()
        assertEquals("/v1/embeddings", entry.at("/path").stringValue())
        assertEquals(REQUEST_ID.toString(), entry.at("/headers/x-client-request-id/0").stringValue())
        assertEquals("bearer", entry.at("/credential/scheme").stringValue())
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
        val body = entry.at("/body").stringValue()
        assertEquals(
            """{"model":"mock-embed","input":"Hi","x_future":[1,null],"dimensions":3,"user":"u-1"}""",
            body,
        )
    }

    @Test
    fun `omitted usage is null`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        assertEquals(null, (embed() as EmbeddingResult.Embedded).usage)
    }

    @Test
    fun `a relayed 400 keeps code and param, with one journal entry`() {
        scenario("""{"otherwise":{"error":{"status":400,"code":"invalid_value","param":"dimensions"}}}""")

        assertEquals(
            EmbeddingResult.Failed(ProviderFailure.RejectedByProvider(400, "invalid_value", "dimensions")),
            embed(),
        )
        assertEquals(1, journal().size)
    }

    @Test
    fun `a 500 is UpstreamError, with one journal entry`() {
        scenario("""{"otherwise":{"error":{"status":500}}}""")

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), embed())
        assertEquals(1, journal().size)
    }

    @Test
    fun `a deadline ends a hang with UpstreamTimeout and the provider sees the call cancelled`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val adapter = adapter(requestTimeout = Duration.ofMillis(500))
        val started = System.nanoTime()

        val result = embed(adapter = adapter)

        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamTimeout), result)
        assertTrue(elapsed < Duration.ofSeconds(3)) { "answered after $elapsed" }
        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }

    @Test
    fun `a closed port is ProviderUnavailable`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = embed(adapter = adapter(baseUrl = "http://127.0.0.1:$port/v1"))

        assertEquals(EmbeddingResult.Failed(ProviderFailure.ProviderUnavailable(null)), result)
    }

    @Test
    fun `a malformed 200 is UpstreamError`() {
        scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""")

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), embed())
        assertEquals(1, journal().size)
    }

    @Test
    fun `a connection closed mid-response is UpstreamError`() {
        scenario("""{"otherwise":{"fault":{"kind":"disconnect"}}}""")

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), embed())
        assertEquals(1, journal().size)
    }

    @Test
    fun `a response over the limit is UpstreamError`() {
        val result = embed(adapter = adapter(maxResponseBytes = 64))

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), result)
        assertEquals(1, journal().size)
    }

    // Unconfined, as a handler runs: on runBlocking's own loop the resume from the lane would wait behind `eventually`.
    @Suppress("InjectDispatcher")
    @Test
    fun `a cancelled caller cancels the upstream call`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = adapter()
        val before = lastSeq()

        runBlocking {
            val call = async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { adapter.embed(call()) }
            eventually { lastSeq() > before }
            call.cancel()
            // Inside the scope, which would otherwise wait for a call that ignored the cancel to reach its deadline.
            eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
        }
    }

    private fun embed(
        call: EmbeddingCall = call(),
        adapter: OpenAiEmbeddingAdapter = adapter(),
    ): EmbeddingResult = runBlocking { adapter.embed(call) }

    private fun call(extras: JsonValue.Object = JsonValue.Object.EMPTY): EmbeddingCall =
        EmbeddingCall(
            REQUEST_ID,
            TestKeys.TEAM_ID,
            Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName(MODEL)),
            EmbeddingInput.Text("Hi"),
            extras,
        )

    private fun scenario(json: String) {
        val request =
            HttpRequest
                .newBuilder(URI.create("${SharedMockProvider.baseUrl}/__mock/scenarios/$MODEL"))
                .header("content-type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 204) { "PUT scenario: ${response.statusCode()} ${response.body()}" }
    }

    private fun journal(): List<JsonNode> = admin("/__mock/requests").get("data").values().toList()

    private fun lastSeq(): Long = admin("/__mock/stats").get("lastSeq").longValue()

    private fun admin(path: String): JsonNode {
        val request = HttpRequest.newBuilder(URI.create("${SharedMockProvider.baseUrl}$path")).GET().build()
        return mapper.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body())
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "not true within 2 s; journal: ${journal()}" }
            Thread.sleep(25)
        }
    }

    private fun fingerprint(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())).take(8)

    private companion object {
        const val KEY = "provider-key-not-real"
        const val MODEL = "mock-embed"
        const val CALLS = 200
        const val CONCURRENCY = 16
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
    }
}
