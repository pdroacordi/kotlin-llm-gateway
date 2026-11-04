package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.chat.StreamOptions
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
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
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

// No retries in the adapter, so one stream is one journal entry; every test can count on that.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class OpenAiChatStreamIntegrationTest {
    private val mapper = JsonMapper.builder().build()
    private val adapters = mutableListOf<OpenAiChatAdapter>()
    private val http = HttpClient.newHttpClient()

    private fun adapter(
        stream: StreamConfig = STREAM,
        maxConnections: Int = 16,
        maxLifeTime: Duration = Duration.ofMinutes(5),
        baseUrl: String = "${SharedMockProvider.baseUrl}/v1",
    ): OpenAiChatAdapter =
        OpenAiChatAdapter(
            OpenAiAdapterConfig(
                baseUrl = URI.create(baseUrl),
                apiKey = ApiKey("provider-key-not-real"),
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = Duration.ofSeconds(10),
                maxResponseBytes = 1_048_576,
                pool =
                    PoolConfig(
                        maxConnections = maxConnections,
                        pendingAcquireTimeout = Duration.ofSeconds(5),
                        maxIdleTime = Duration.ofSeconds(30),
                        maxLifeTime = maxLifeTime,
                    ),
                stream = stream,
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

    // First in the class, so the first stream in this JVM is the one BlockHound sees.
    @Test
    @Order(1)
    fun `200 streams and 50 cancelled pass BlockHound on a non-blocking thread, the first one included`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")
        val adapter = adapter()
        adapter.warmUp()

        val streams =
            Flux
                .range(0, STREAMS)
                .flatMap({ onNonBlockingThread { adapter.stream(call()).toList() } }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()
        val cancelled =
            Flux
                .range(0, CANCELLED)
                .flatMap({ onNonBlockingThread { adapter.stream(call()).first() } }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(STREAMS, streams.size)
        assertTrue(streams.all { it.last() == ProviderStreamEvent.Completed }) { streams.first().toString() }
        assertEquals(CANCELLED, cancelled.size)
    }

    // Spring starts coroutine handlers Unconfined, so the adapter runs on the calling thread, as here.
    @Suppress("InjectDispatcher")
    private fun <T : Any> onNonBlockingThread(block: suspend () -> T) =
        mono(Dispatchers.Unconfined) { block() }.subscribeOn(Schedulers.parallel())

    @ParameterizedTest
    @ValueSource(strings = ["stall", "disconnect"])
    fun `the code after a timeout or an abort runs on a Reactor thread`(kind: String) {
        scenario("""{"otherwise":{"fault":{"kind":"$kind","afterPieces":1}}}""")
        val adapter = adapter(STREAM.copy(idleTimeout = Duration.ofMillis(300)))

        val thread =
            onNonBlockingThread {
                adapter.stream(call()).toList()
                Thread.currentThread()
            }.block(BLOCK_LIMIT)

        assertTrue(thread != null && Schedulers.isNonBlockingThread(thread)) { thread?.name.orEmpty() }
    }

    @Test
    fun `a success gives its chunks, the usage chunk and Completed`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val events = stream()

        val chunks = events.filterIsInstance<ProviderStreamEvent.Chunk>().map { it.chunk }
        assertEquals(
            "Hi there",
            chunks.joinToString("") { c -> c.choices.joinToString("") { it.delta.content.orEmpty() } },
        )
        assertEquals("stop", chunks.flatMap { it.choices }.mapNotNull { it.finishReason?.value }.single())
        assertTrue(chunks.last().choices.isEmpty())
        assertFalse(checkNotNull(chunks.last().usage).estimated)
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `the provider sees stream true, include_usage, an event-stream Accept and the request ID`() {
        val extras = JsonValue.Object(mapOf("include_obfuscation" to JsonValue.Bool(false)))

        stream(call = call(StreamOptions(includeUsage = false, extras = extras)))

        val entry = journal().single()
        assertEquals(REQUEST_ID.toString(), entry.at("/headers/x-client-request-id/0").stringValue())
        assertEquals("text/event-stream", entry.at("/headers/accept/0").stringValue())
        val body = mapper.readTree(entry.at("/body").stringValue())
        assertTrue(body.get("stream").booleanValue())
        assertTrue(body.at("/stream_options/include_usage").booleanValue())
        assertFalse(body.at("/stream_options/include_obfuscation").booleanValue())
        assertEquals("completed", entry.at("/outcome/kind").stringValue())
    }

    @Test
    fun `usage omit gives no usage chunk, and Completed`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val events = stream()

        assertTrue(events.filterIsInstance<ProviderStreamEvent.Chunk>().all { it.chunk.usage == null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `an upstream status before the body is a Failed first, with one journal entry`() {
        scenario("""{"otherwise":{"error":{"status":429,"headers":{"retry-after":"7"}}}}""")

        val event = stream().single() as ProviderStreamEvent.Failed

        assertTrue(event.failure is ProviderFailure.UpstreamRateLimited) { event.toString() }
        assertEquals(1, journal().size)
    }

    @Test
    fun `a closed port is ProviderUnavailable`() {
        val events = stream(adapter(baseUrl = "http://127.0.0.1:1/v1"))

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.ProviderUnavailable(null))), events)
    }

    @Test
    fun `hang at the first-byte deadline is UpstreamTimeout, and the provider sees the call cancelled`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val events = stream(adapter(STREAM.copy(firstByteTimeout = Duration.ofMillis(500))))

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)), events)
        eventually { outcome() == "client_cancelled" }
    }

    @Test
    fun `stall at the idle deadline gives the pieces so far, then UpstreamTimeout`() {
        scenario("""{"otherwise":{"fault":{"kind":"stall","afterPieces":2}}}""")

        val events = stream(adapter(STREAM.copy(idleTimeout = Duration.ofMillis(500))))

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout), events.last())
        assertEquals(3, events.count { it is ProviderStreamEvent.Chunk })
        eventually { outcome() == "client_cancelled" }
    }

    @Test
    fun `the total deadline cuts a stream that never goes idle`() {
        scenario(
            """{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"$LONG"}}}""",
        )

        val events = stream(adapter(STREAM.copy(totalTimeout = Duration.ofSeconds(1))))

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout), events.last())
        assertTrue(events.size > 2) { events.size.toString() }
        eventually { outcome() == "client_cancelled" }
    }

    @Test
    fun `disconnect mid-body is UpstreamError after the pieces so far`() {
        scenario("""{"otherwise":{"fault":{"kind":"disconnect","afterPieces":2}}}""")

        val events = stream()

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
        assertEquals(3, events.count { it is ProviderStreamEvent.Chunk })
        assertEquals(1, journal().size)
    }

    @Test
    fun `a malformed event ends the stream with UpstreamError, and the rest is not read`() {
        scenario("""{"otherwise":{"fault":{"kind":"malformed","afterPieces":2}}}""")

        val events = stream()

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
        assertEquals(3, events.count { it is ProviderStreamEvent.Chunk })
    }

    @Test
    fun `stream_error maps by its in-band type`() {
        scenario(
            """{"otherwise":{"error":{"status":429,"type":"rate_limit_error","message":"canary"},
               "fault":{"kind":"stream_error","afterPieces":1}}}""",
        )

        val events = stream()

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamRateLimited(null)), events.last())
        assertEquals(1, journal().size)
    }

    @Test
    fun `an event over the limit is UpstreamError, and a stream longer than the limit in total completes`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"$LONG"}}}""")

        val small = stream(adapter(STREAM.copy(maxEventBytes = 100)))
        val whole = stream(adapter(STREAM.copy(maxEventBytes = 1024)))

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), small)
        assertEquals(ProviderStreamEvent.Completed, whole.last())
    }

    @Test
    fun `a cancelled collector leaves client_cancelled, with fewer pieces sent than the total`() {
        scenario("""{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"$LONG"}}}""")
        val adapter = adapter()

        runBlocking { adapter.stream(call()).first() }

        eventually { outcome() == "client_cancelled" }
        assertTrue(journal().single().at("/outcome/piecesSent").intValue() < WORDS)
    }

    @Test
    fun `cancelling a collector that waits for the first byte cancels the upstream call`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = adapter()
        val before = lastSeq()

        runBlocking {
            val collecting = async(start = CoroutineStart.UNDISPATCHED) { adapter.stream(call()).toList() }
            eventually { lastSeq() > before }
            collecting.cancel()
        }

        eventually { outcome() == "client_cancelled" }
    }

    @Test
    fun `a stream outlives pool max-life-time`() {
        scenario(
            """{"otherwise":{"latency":{"perTokenMs":200},
               "content":{"mode":"fixed","text":"a b c d e f g h i j k"}}}""",
        )

        val events = stream(adapter(maxLifeTime = Duration.ofSeconds(1)))

        assertEquals(ProviderStreamEvent.Completed, events.last())
        assertEquals("completed", outcome())
    }

    @Test
    fun `the pool recovers after 20 cancelled streams, with 2 connections`() {
        scenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$LONG"}}}""")
        val adapter = adapter(maxConnections = 2)

        repeat(CANCELLED_IN_A_ROW) { runBlocking { adapter.stream(call()).first() } }
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi"}}}""")

        assertEquals(ProviderStreamEvent.Completed, stream(adapter).last())
    }

    private fun stream(
        adapter: OpenAiChatAdapter = adapter(),
        call: ChatCall = call(),
    ): List<ProviderStreamEvent> = runBlocking { adapter.stream(call).toList() }

    private fun call(options: StreamOptions? = null): ChatCall =
        ChatCall(
            REQUEST_ID,
            Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName(MODEL)),
            ChatRequest(
                ModelName(MODEL),
                listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))),
                streamOptions = options,
            ),
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

    private fun outcome(): String? = journal().singleOrNull()?.at("/outcome/kind")?.stringValue()

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

    private companion object {
        const val MODEL = "mock-chat"
        const val STREAMS = 200
        const val CANCELLED = 50
        const val CANCELLED_IN_A_ROW = 20
        const val CONCURRENCY = 16
        const val WORDS = 200
        val BLOCK_LIMIT: Duration = Duration.ofMinutes(2)
        val LONG = "word ".repeat(WORDS)
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val STREAM = StreamConfig(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(30), 1_048_576)
    }
}
