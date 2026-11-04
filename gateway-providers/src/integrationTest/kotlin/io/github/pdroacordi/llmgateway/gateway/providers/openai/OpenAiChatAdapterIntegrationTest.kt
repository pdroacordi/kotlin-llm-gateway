package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamConfig
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

// No retries in the adapter, so one call is one journal entry; every test can count on that.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OpenAiChatAdapterIntegrationTest {
    private val mapper = JsonMapper.builder().build()
    private val adapters = mutableListOf<OpenAiChatAdapter>()
    private val http = HttpClient.newHttpClient()

    private fun adapter(
        baseUrl: String = "${SharedMockProvider.baseUrl}/v1",
        requestTimeout: Duration = Duration.ofSeconds(10),
        maxResponseBytes: Int = 1_048_576,
        maxConnections: Int = 16,
        pendingAcquireTimeout: Duration = Duration.ofSeconds(5),
    ): OpenAiChatAdapter =
        OpenAiChatAdapter(
            OpenAiAdapterConfig(
                baseUrl = URI.create(baseUrl),
                apiKey = ApiKey(KEY),
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = requestTimeout,
                maxResponseBytes = maxResponseBytes,
                pool =
                    PoolConfig(
                        maxConnections = maxConnections,
                        pendingAcquireTimeout = pendingAcquireTimeout,
                        maxIdleTime = Duration.ofSeconds(30),
                        maxLifeTime = Duration.ofMinutes(5),
                    ),
                stream = StreamConfig(requestTimeout, requestTimeout, requestTimeout, maxResponseBytes),
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
        assertTrue(results.all { it is ProviderResult.Completed }) { results.toString() }
    }

    // Spring starts coroutine handlers Unconfined, so the adapter runs on the calling thread, as here.
    @Suppress("InjectDispatcher")
    private fun onNonBlockingThread(adapter: OpenAiChatAdapter) =
        mono(Dispatchers.Unconfined) { adapter.complete(call()) }.subscribeOn(Schedulers.parallel())

    @Test
    fun `a success is read, with usage and extras`() {
        val result = complete() as ProviderResult.Completed

        assertEquals(ModelName(MODEL), result.completion.model)
        assertEquals(1, result.completion.choices.size)
        assertEquals(JsonValue.Text("fp_mock"), result.completion.extras.members["system_fingerprint"])
        val usage = checkNotNull(result.usage)
        assertFalse(usage.estimated)
        assertTrue(usage.inputTokens.value > 0) { usage.toString() }
    }

    @Test
    fun `the provider sees the configured key, the request ID and the body, and nothing else from the client`() {
        val extras =
            JsonValue.Object(
                linkedMapOf(
                    "x_future" to
                        JsonValue.Object(
                            mapOf("a" to JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null))),
                        ),
                    "seed" to JsonValue.Number("9007199254740993"),
                    "temperature" to JsonValue.Number("0.7"),
                ),
            )

        complete(call(extras = extras))

        val entry = journal().single()
        assertEquals(REQUEST_ID.toString(), entry.at("/headers/x-client-request-id/0").stringValue())
        assertEquals("bearer", entry.at("/credential/scheme").stringValue())
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
        assertEquals("application/json", entry.at("/headers/content-type/0").stringValue())
        val body = entry.at("/body").stringValue()
        assertTrue(""""x_future":{"a":[1,null]}""" in body) { body }
        assertTrue(""""seed":9007199254740993""" in body) { body }
        assertTrue(""""temperature":0.7""" in body) { body }
    }

    @Test
    fun `usage omit gives a completion with no usage`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        assertEquals(null, (complete() as ProviderResult.Completed).usage)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedErrors")
    fun `an injected error maps to its failure, with one journal entry`(
        name: String,
        error: String,
        expected: ProviderFailure,
    ) {
        scenario("""{"otherwise":{"error":$error}}""")

        assertEquals(ProviderResult.Failed(expected), complete(), name)
        assertEquals(1, journal().size)
    }

    @Test
    fun `a deadline ends a hang with UpstreamTimeout and the provider sees the call cancelled`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val result = complete(adapter = adapter(requestTimeout = Duration.ofMillis(500)))

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamTimeout), result)
        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }

    @Test
    fun `a closed port is ProviderUnavailable`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = complete(adapter = adapter(baseUrl = "http://127.0.0.1:$port/v1"))

        assertEquals(ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)), result)
    }

    @Test
    fun `an unknown host is ProviderUnavailable`() {
        // A deadline well past the 5 s DNS query timeout, so a slow resolver cannot turn this into UpstreamTimeout.
        val adapter = adapter(baseUrl = "http://no-such-host.invalid/v1", requestTimeout = Duration.ofSeconds(60))

        val result = complete(adapter = adapter)

        assertEquals(ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)), result)
    }

    // A pool acquire timeout is a TimeoutException too; WebClient wraps it, so it is not taken for our deadline.
    @Test
    fun `a pool with no free connection is ProviderUnavailable`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = adapter(maxConnections = 1, pendingAcquireTimeout = Duration.ofMillis(200))
        val before = lastSeq()

        runBlocking {
            val holder = async(start = CoroutineStart.UNDISPATCHED) { adapter.complete(call()) }
            eventually { lastSeq() > before }

            assertEquals(ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)), adapter.complete(call()))
            holder.cancel()
        }
    }

    // One connection, so a queue of two: the fourth call is refused at once.
    @Test
    fun `a full pool queue is ProviderUnavailable`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = adapter(maxConnections = 1, pendingAcquireTimeout = Duration.ofSeconds(8))
        val before = lastSeq()

        runBlocking {
            val held = List(3) { async(start = CoroutineStart.UNDISPATCHED) { adapter.complete(call()) } }
            eventually { lastSeq() > before }

            assertEquals(ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)), adapter.complete(call()))
            held.forEach { it.cancel() }
        }
    }

    @Test
    fun `a malformed 200 is UpstreamError`() {
        scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""")

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete())
        assertEquals(1, journal().size)
    }

    @Test
    fun `a connection closed mid-response is UpstreamError`() {
        scenario("""{"otherwise":{"fault":{"kind":"disconnect"}}}""")

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete())
        assertEquals(1, journal().size)
    }

    @Test
    fun `a response over the limit is UpstreamError`() {
        val result = complete(adapter = adapter(maxResponseBytes = 64))

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), result)
    }

    @Test
    fun `a cancelled caller cancels the upstream call`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = adapter()
        val before = lastSeq()

        runBlocking {
            val call = async(start = CoroutineStart.UNDISPATCHED) { adapter.complete(call()) }
            eventually { lastSeq() > before }
            call.cancel()
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }

    private fun complete(
        call: ChatCall = call(),
        adapter: OpenAiChatAdapter = adapter(),
    ): ProviderResult = runBlocking { adapter.complete(call) }

    private fun call(extras: JsonValue.Object = JsonValue.Object.EMPTY): ChatCall =
        ChatCall(
            REQUEST_ID,
            Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName(MODEL)),
            ChatRequest(ModelName(MODEL), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))), extras),
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

    companion object {
        private const val KEY = "provider-key-not-real"
        private const val MODEL = "mock-chat"
        private const val CALLS = 200
        private const val CONCURRENCY = 16
        private val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))

        @JvmStatic
        fun injectedErrors(): List<Arguments> =
            listOf(
                Arguments.of(
                    "400 with code and param",
                    """{"status":400,"code":"context_length_exceeded","param":"messages[0].content"}""",
                    ProviderFailure.RejectedByProvider(400, "context_length_exceeded", "messages[0].content"),
                ),
                Arguments.of("413", """{"status":413}""", ProviderFailure.RejectedByProvider(413, null, null)),
                Arguments.of("422", """{"status":422}""", ProviderFailure.RejectedByProvider(422, null, null)),
                Arguments.of("401", """{"status":401}""", ProviderFailure.UpstreamAuthFailed),
                Arguments.of("403", """{"status":403}""", ProviderFailure.UpstreamAuthFailed),
                Arguments.of("404 model", """{"status":404}""", ProviderFailure.ModelNotFound),
                Arguments.of("404 other", """{"status":404,"code":"nope"}""", ProviderFailure.UpstreamError),
                Arguments.of("408", """{"status":408}""", ProviderFailure.UpstreamTimeout),
                Arguments.of(
                    "429 with retry-after",
                    """{"status":429,"retryAfterSeconds":7}""",
                    ProviderFailure.UpstreamRateLimited(7.seconds),
                ),
                Arguments.of(
                    "429 quota",
                    """{"status":429,"type":"insufficient_quota","code":"insufficient_quota"}""",
                    ProviderFailure.UpstreamQuotaExhausted,
                ),
                Arguments.of("500", """{"status":500}""", ProviderFailure.UpstreamError),
                Arguments.of("502", """{"status":502}""", ProviderFailure.UpstreamError),
                Arguments.of(
                    "503 with retry-after-ms",
                    """{"status":503,"retryAfterMs":7000}""",
                    ProviderFailure.ProviderUnavailable(7.seconds),
                ),
                Arguments.of("504", """{"status":504}""", ProviderFailure.UpstreamTimeout),
            )
    }
}
