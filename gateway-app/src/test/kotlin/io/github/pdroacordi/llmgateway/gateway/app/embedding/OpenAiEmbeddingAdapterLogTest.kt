package io.github.pdroacordi.llmgateway.gateway.app.embedding

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
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiEmbeddingAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiEmbeddingAdapterConfig
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Here, not in `gateway-providers`: this module has a log backend to capture. */
@ExtendWith(OutputCaptureExtension::class)
@Timeout(30)
class OpenAiEmbeddingAdapterLogTest {
    @Volatile
    private var status = 200

    @Volatile
    private var retryAfter: String? = null

    @Volatile
    private var reply = ""

    @Volatile
    private var onRequest: () -> Unit = {}

    private val provider: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/v1/embeddings") { exchange ->
                exchange.requestBody.readAllBytes()
                onRequest()
                retryAfter?.let { exchange.responseHeaders.add("Retry-After", it) }
                val bytes = reply.encodeToByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private val adapter =
        OpenAiEmbeddingAdapter(
            OpenAiEmbeddingAdapterConfig(
                baseUrl = URI.create("http://127.0.0.1:${provider.address.port}/v1"),
                apiKey = ApiKey("provider-key-not-real"),
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = Duration.ofSeconds(5),
                maxResponseBytes = 1_048_576,
                pool = PoolConfig(4, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)),
                cpuThreads = 2,
                cpuQueueSize = 64,
            ),
            JsonMapper.builder().build(),
            Clock.systemUTC(),
        )

    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    @AfterEach
    fun stop() {
        adapter.close()
        provider.stop(0)
    }

    private fun failedLines(output: CapturedOutput): List<String> =
        output.all
            .substring(mark)
            .lines()
            .filter { "Provider call failed" in it }

    private fun embed(): EmbeddingResult = runBlocking { adapter.embed(CALL) }

    @Test
    fun `an unreadable 2xx logs one failure with no fields, whatever error object it holds`(output: CapturedOutput) {
        reply = """{"error":{"code":"canary_code","type":"canary_type"}}"""

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), embed())

        val line = failedLines(output).single()
        assertTrue("status=200" in line) { line }
        assertTrue("code=null" in line) { line }
        assertTrue("type=null" in line) { line }
        assertFalse("canary" in line) { line }
    }

    @Test
    fun `a non-2xx logs one failure with the provider's code and type`(output: CapturedOutput) {
        status = 500
        reply = """{"error":{"code":"server_code","type":"server_type"}}"""

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamError), embed())

        val line = failedLines(output).single()
        assertTrue("status=500" in line) { line }
        assertTrue("code=server_code" in line) { line }
        assertTrue("type=server_type" in line) { line }
    }

    @Test
    fun `a 429 keeps the provider's Retry-After`() {
        status = 429
        retryAfter = "7"
        reply = """{"error":{"code":"rate_limit_exceeded"}}"""

        assertEquals(EmbeddingResult.Failed(ProviderFailure.UpstreamRateLimited(7.seconds)), embed())
    }

    @Test
    fun `a 503 keeps the provider's Retry-After`() {
        status = 503
        retryAfter = "9"
        reply = "{}"

        assertEquals(EmbeddingResult.Failed(ProviderFailure.ProviderUnavailable(9.seconds)), embed())
    }

    // Spring starts coroutine handlers Unconfined, so the adapter's callers run as these do.
    @Suppress("InjectDispatcher")
    @Test
    fun `a refusal for the pool's pending limit logs no provider failure`(output: CapturedOutput) {
        val release = CountDownLatch(1)
        onRequest = { release.await(HOLD_SECONDS, TimeUnit.SECONDS) }
        reply = """{"data":[{"index":0,"embedding":[0.5]}],"model":"m"}"""
        val refused = AtomicInteger()

        val results =
            try {
                runBlocking {
                    val calls =
                        List(POOL_ADMITTED + REFUSED) {
                            async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                                adapter.embed(CALL).also { if (it == EmbeddingResult.Busy) refused.incrementAndGet() }
                            }
                        }
                    withTimeout(WAIT_LIMIT_MS) { while (refused.get() < REFUSED) delay(POLL_MS) }
                    release.countDown()
                    withTimeout(WAIT_LIMIT_MS) { calls.awaitAll() }
                }
            } finally {
                release.countDown()
            }

        assertEquals(REFUSED, results.count { it == EmbeddingResult.Busy }) { results.toString() }
        assertEquals(emptyList<String>(), failedLines(output))
    }

    private companion object {
        // Four connections, and two waiting for each: UpstreamClient's PENDING_PER_CONNECTION.
        const val POOL_ADMITTED = 12
        const val REFUSED = 3
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
