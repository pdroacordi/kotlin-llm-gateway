package io.github.pdroacordi.llmgateway.gateway.providers.openai

import com.sun.management.ThreadMXBean
import com.sun.net.httpserver.HttpServer
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.ThrowingSupplier
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.StringNode
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** The real adapter against an in-JVM provider, so no Docker; [Reads] sees each tree read of the code in a body. */
@Timeout(30)
class OpenAiEmbeddingAdapterFailureTest {
    class Reads : JsonNodeFactory() {
        val threads = CopyOnWriteArrayList<Thread>()

        override fun stringNode(text: String?): StringNode {
            if (text == CODE) threads += Thread.currentThread()
            return super.stringNode(text)
        }
    }

    private val reads = Reads()
    private val mapper = JsonMapper.builder().nodeFactory(reads).build()
    private val adapters = mutableListOf<OpenAiEmbeddingAdapter>()

    @Volatile
    private var status = 200

    @Volatile
    private var reply = ByteArray(0)

    @Volatile
    private var onRequest: () -> Unit = {}

    private val provider: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/v1/embeddings") { exchange ->
                exchange.requestBody.readAllBytes()
                onRequest()
                exchange.sendResponseHeaders(status, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
            }
            start()
        }

    private fun config(
        requestTimeout: Duration,
        maxResponseBytes: Int = 1_048_576,
    ): OpenAiEmbeddingAdapterConfig =
        OpenAiEmbeddingAdapterConfig(
            baseUrl = URI.create("http://127.0.0.1:${provider.address.port}/v1"),
            apiKey = ApiKey("provider-key-not-real"),
            connectTimeout = Duration.ofSeconds(5),
            requestTimeout = requestTimeout,
            maxResponseBytes = maxResponseBytes,
            pool = PoolConfig(4, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)),
            cpuThreads = 2,
            cpuQueueSize = 64,
        )

    private fun adapter(): OpenAiEmbeddingAdapter =
        OpenAiEmbeddingAdapter(config(Duration.ofSeconds(5)), mapper, Clock.systemUTC()).also { adapters += it }

    @AfterEach
    fun stop() {
        adapters.forEach { it.close() }
        provider.stop(0)
    }

    // Spring starts coroutine handlers Unconfined, so the adapter runs on the calling thread, as here.
    @Suppress("InjectDispatcher")
    private fun embed(adapter: OpenAiEmbeddingAdapter = adapter()): EmbeddingResult =
        runBlocking(Dispatchers.Unconfined) { adapter.embed(CALL) }

    /** An error body of exactly [size] bytes that names [CODE]. */
    private fun errorBody(size: Int): ByteArray {
        val start = """{"error":{"code":"$CODE","param":"dimensions"},"pad":""""
        val end = """"}"""
        return (start + "x".repeat(size - start.length - end.length) + end).encodeToByteArray()
    }

    @Test
    fun `a small error body is read once, off the event loops`() {
        status = 400
        reply = errorBody(1024)

        val result = embed()

        assertEquals(EmbeddingResult.Failed(ProviderFailure.RejectedByProvider(400, CODE, "dimensions")), result)
        assertEquals(1, reads.threads.size) { reads.threads.toString() }
        val reader = reads.threads.single()
        assertTrue(reader.name.startsWith(LANE)) { reader.name }
    }

    @Test
    fun `an error body at the guard is read`() {
        status = 400
        reply = errorBody(GUARD)

        assertEquals(EmbeddingResult.Failed(ProviderFailure.RejectedByProvider(400, CODE, "dimensions")), embed())
    }

    @Test
    fun `an error body over the guard is the same failure with no fields, and is never parsed`() {
        status = 400
        reply = errorBody(GUARD + 1)

        assertEquals(EmbeddingResult.Failed(ProviderFailure.RejectedByProvider(400, null, null)), embed())
        assertTrue(reads.threads.isEmpty()) { reads.threads.toString() }
    }

    @Test
    fun `a JVM-fatal error in the scan ends the call with UpstreamTimeout at the deadline`() {
        reply = """{"data":[]}""".encodeToByteArray()
        val fatal = { _: ByteArray -> throw OutOfMemoryError("not a real one") }
        val adapter = OpenAiEmbeddingAdapter(config(Duration.ofMillis(300)), mapper, Clock.systemUTC(), fatal)
        adapters += adapter

        val result = assertTimeoutPreemptively(Duration.ofSeconds(3), ThrowingSupplier { embed(adapter) })

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamTimeout), result)
    }

    @Test
    fun `a large reply is copied to an array on the lane, not on an event loop`() {
        val adapter =
            OpenAiEmbeddingAdapter(config(Duration.ofSeconds(5), LARGE_LIMIT), mapper, Clock.systemUTC()).also {
                adapters += it
            }
        reply = """{"data":[{"index":0,"embedding":"AAAAPw=="}],"model":"m"}""".encodeToByteArray()
        assertTrue(embed(adapter) is EmbeddingResult.Embedded)
        val vector = "A".repeat(LARGE_REPLY_BYTES)
        reply = """{"data":[{"index":0,"embedding":"$vector"}],"model":"m"}""".encodeToByteArray()
        val before = allocatedOnEventLoops()

        val result = embed(adapter)

        val allocated = allocatedOnEventLoops() - before
        assertTrue(result is EmbeddingResult.Embedded) { result.toString() }
        assertTrue(allocated < LARGE_REPLY_BYTES / 2) { "event loops allocated $allocated" }
    }

    private fun allocatedOnEventLoops(): Long {
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        return threads
            .getThreadInfo(threads.allThreadIds)
            .filterNotNull()
            .filter { it.threadName.startsWith("reactor-http-") }
            .sumOf { threads.getThreadAllocatedBytes(it.threadId) }
    }

    @Test
    fun `a lane that refuses the request's write answers Busy, and nothing is sent`() {
        val adapter = adapter()
        var sent = false
        onRequest = { sent = true }
        adapter.lane.close()

        assertEquals(EmbeddingResult.Busy, embed(adapter))
        assertFalse(sent)
    }

    @Test
    fun `a lane that refuses the reply's copy answers Busy`() {
        val adapter = adapter()
        reply = """{"data":[{"index":0,"embedding":[0.5]}],"model":"m"}""".encodeToByteArray()
        onRequest = { adapter.lane.close() }

        assertEquals(EmbeddingResult.Busy, embed(adapter))
    }

    /** Keeps a lane of one thread and one queue slot full, as parses do under load, while [block] runs. */
    @Suppress("InjectDispatcher")
    private fun <T> withLaneFull(
        adapter: OpenAiEmbeddingAdapter,
        block: () -> T,
    ): T {
        val done = AtomicBoolean()
        val parses =
            List(PARSERS) {
                thread(isDaemon = true) {
                    while (!done.get()) {
                        try {
                            runBlocking(Dispatchers.Unconfined) { adapter.lane.run { 1 } }
                        } catch (_: RejectedExecutionException) {
                            // A refused parse is what this load is for; the next one tries again.
                        }
                    }
                }
            }
        return try {
            block()
        } finally {
            done.set(true)
            parses.forEach { it.join(JOIN_LIMIT_MS) }
        }
    }

    private fun narrowLaneAdapter(): OpenAiEmbeddingAdapter =
        OpenAiEmbeddingAdapter(
            config(Duration.ofSeconds(5)).copy(cpuThreads = 1, cpuQueueSize = 1),
            mapper,
            Clock.systemUTC(),
        ).also { adapters += it }

    @Test
    fun `a full lane never drops a reply the provider has answered`() {
        val adapter = narrowLaneAdapter()
        val sent = AtomicInteger()
        onRequest = { sent.incrementAndGet() }
        reply = """{"data":[{"index":0,"embedding":[0.5]}],"model":"m"}""".encodeToByteArray()

        val results = withLaneFull(adapter) { List(CALLS_UNDER_LOAD) { embed(adapter) } }

        assertEquals(CALLS_UNDER_LOAD, sent.get())
        assertEquals(emptyList<EmbeddingResult>(), results.filterNot { it is EmbeddingResult.Embedded })
    }

    @Test
    fun `a full lane never drops a provider's error body`() {
        val adapter = narrowLaneAdapter()
        status = 400
        reply = errorBody(1024)
        val rejected = EmbeddingResult.Failed(ProviderFailure.RejectedByProvider(400, CODE, "dimensions"))

        val results = withLaneFull(adapter) { List(CALLS_UNDER_LOAD) { embed(adapter) } }

        assertEquals(emptyList<EmbeddingResult>(), results.filterNot { it == rejected })
    }

    // Spring starts coroutine handlers Unconfined, so the adapter's callers run as these do.
    @Suppress("InjectDispatcher")
    @Test
    fun `the pool's pending limit answers Busy, not a provider failure, and sends nothing`() {
        val base = config(Duration.ofSeconds(20))
        val narrow = base.copy(pool = base.pool.copy(maxConnections = 1))
        val adapter = OpenAiEmbeddingAdapter(narrow, mapper, Clock.systemUTC()).also { adapters += it }
        val release = CountDownLatch(1)
        val sent = AtomicInteger()
        onRequest = {
            sent.incrementAndGet()
            release.await(HOLD_SECONDS, TimeUnit.SECONDS)
        }
        reply = """{"data":[{"index":0,"embedding":[0.5]}],"model":"m"}""".encodeToByteArray()
        val refused = AtomicInteger()

        val results =
            try {
                runBlocking {
                    val calls =
                        List(POOL_CALLS) {
                            async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                                adapter.embed(CALL).also {
                                    if (it !is EmbeddingResult.Embedded) refused.incrementAndGet()
                                }
                            }
                        }
                    withTimeout(WAIT_LIMIT_MS) { while (refused.get() < POOL_CALLS - POOL_ADMITTED) delay(POLL_MS) }
                    release.countDown()
                    withTimeout(WAIT_LIMIT_MS) { calls.awaitAll() }
                }
            } finally {
                release.countDown()
            }

        assertEquals(POOL_CALLS - POOL_ADMITTED, results.count { it == EmbeddingResult.Busy }) { results.toString() }
        assertEquals(POOL_ADMITTED, results.count { it is EmbeddingResult.Embedded }) { results.toString() }
        assertEquals(POOL_ADMITTED, sent.get())
    }

    @Test
    fun `close disposes the adapter's lane`() {
        val adapter = adapter()

        adapter.close()

        assertThrows<RejectedExecutionException> { runBlocking { adapter.lane.run { 1 } } }
    }

    private companion object {
        const val CODE = "canary_code"
        const val LANE = "embeddings-openai-cpu-"
        const val GUARD = 64 * 1024
        const val LARGE_REPLY_BYTES = 8 * 1024 * 1024
        const val LARGE_LIMIT = 16 * 1024 * 1024
        const val PARSERS = 4
        const val CALLS_UNDER_LOAD = 200
        const val JOIN_LIMIT_MS = 5000L

        // One connection, and two waiting for it: UpstreamClient's PENDING_PER_CONNECTION.
        const val POOL_ADMITTED = 3
        const val POOL_CALLS = 6
        const val HOLD_SECONDS = 20L
        const val WAIT_LIMIT_MS = 10_000L
        const val POLL_MS = 10L
        val CALL =
            EmbeddingCall(
                RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                TestKeys.TEAM_ID,
                Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("m")),
                EmbeddingInput.Text("a"),
            )
    }
}
