package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
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
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import kotlin.time.Duration.Companion.seconds

// No retries in the adapter, so one call is one journal entry; every test can count on that.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnthropicChatAdapterIntegrationTest {
    private val fixture = AnthropicMockFixture()

    @BeforeEach
    fun resetMock() {
        SharedMockProvider.reset()
    }

    @AfterEach
    fun closeAdapters() {
        fixture.close()
    }

    // First in the class, so the first request in this JVM is the one BlockHound sees.
    @Test
    @Order(1)
    fun `200 calls pass BlockHound on a non-blocking thread, the first one included`() {
        val adapter = fixture.adapter()
        adapter.warmUp()
        val extras = JsonValue.Object(mapOf("user" to JsonValue.Text("someone")))

        val results =
            Flux
                .range(0, CALLS)
                .flatMap({ onNonBlockingThread(adapter, extras) }, CONCURRENCY)
                .collectList()
                .block()
                .orEmpty()

        assertEquals(CALLS, results.size)
        assertTrue(results.all { it is ProviderResult.Completed }) { results.toString() }
    }

    // Spring starts coroutine handlers Unconfined, so the adapter runs on the calling thread, as here.
    @Suppress("InjectDispatcher")
    private fun onNonBlockingThread(
        adapter: AnthropicChatAdapter,
        extras: JsonValue.Object,
    ) = mono(Dispatchers.Unconfined) { adapter.complete(AnthropicMockFixture.call(extras)) }
        .subscribeOn(Schedulers.parallel())

    @Test
    fun `a success is read, with usage`() {
        val result = complete() as ProviderResult.Completed

        assertEquals(1, result.completion.choices.size)
        assertTrue(result.completion.id.startsWith("msg_mock_")) { result.completion.id }
        val usage = checkNotNull(result.usage)
        assertFalse(usage.estimated)
        assertTrue(usage.inputTokens.value > 0) { usage.toString() }
    }

    @Test
    fun `the provider sees the configured key, the version, the cap and the request ID, and no Authorization`() {
        complete()

        val entry = fixture.journal().single()
        assertEquals("anthropic.messages", entry.at("/route").stringValue())
        assertEquals(
            AnthropicMockFixture.REQUEST_ID.toString(),
            entry.at("/headers/x-client-request-id/0").stringValue(),
        )
        assertEquals("x-api-key", entry.at("/credential/scheme").stringValue())
        assertEquals(fingerprint(AnthropicMockFixture.KEY), entry.at("/credential/fingerprint").stringValue())
        assertEquals("2023-06-01", entry.at("/headers/anthropic-version/0").stringValue())
        assertTrue(entry.at("/headers/authorization").isMissingNode) { entry.toString() }
        assertTrue(""""max_tokens":4096""" in entry.at("/body").stringValue()) { entry.toString() }
    }

    @Test
    fun `a refused request has no upstream call`() {
        val before = fixture.lastSeq()
        val extras = JsonValue.Object(mapOf("seed" to JsonValue.Number("1")))

        val result = complete(AnthropicMockFixture.call(extras))

        assertEquals(
            ProviderResult.Failed(ProviderFailure.UnsupportedByProvider("unsupported_parameter", "seed")),
            result,
        )
        assertEquals(before, fixture.lastSeq())
    }

    @Test
    fun `usage omit gives a completion with no usage`() {
        fixture.scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        assertEquals(null, (complete() as ProviderResult.Completed).usage)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("injectedErrors")
    fun `an injected error maps to its failure, with one journal entry`(
        name: String,
        error: String,
        expected: ProviderFailure,
    ) {
        fixture.scenario("""{"otherwise":{"error":$error}}""")

        assertEquals(ProviderResult.Failed(expected), complete(), name)
        assertEquals(1, fixture.journal().size)
    }

    @Test
    fun `a deadline ends a hang with UpstreamTimeout and the provider sees the call cancelled`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val result = complete(adapter = fixture.adapter(requestTimeout = Duration.ofMillis(500)))

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamTimeout), result)
        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    @Test
    fun `a closed port is ProviderUnavailable`() {
        val port = ServerSocket(0).use { it.localPort }

        val result = complete(adapter = fixture.adapter(baseUrl = "http://127.0.0.1:$port/v1"))

        assertEquals(ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)), result)
    }

    @Test
    fun `a pool with no free connection is ProviderUnavailable`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter =
            fixture.adapter(pool = SINGLE_CONNECTION)
        val before = fixture.lastSeq()

        runBlocking {
            val holder =
                async(start = CoroutineStart.UNDISPATCHED) { adapter.complete(AnthropicMockFixture.call()) }
            fixture.eventually { fixture.lastSeq() > before }

            assertEquals(
                ProviderResult.Failed(ProviderFailure.ProviderUnavailable(null)),
                adapter.complete(AnthropicMockFixture.call()),
            )
            holder.cancel()
        }
    }

    @Test
    fun `a malformed 200 is UpstreamError`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""")

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete())
        assertEquals(1, fixture.journal().size)
    }

    @Test
    fun `a connection closed mid-response is UpstreamError`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"disconnect"}}}""")

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete())
        assertEquals(1, fixture.journal().size)
    }

    @Test
    fun `a response over the limit is UpstreamError`() {
        val result = complete(adapter = fixture.adapter(maxResponseBytes = 64))

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), result)
    }

    @Test
    fun `a cancelled caller cancels the upstream call`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = fixture.adapter()
        val before = fixture.lastSeq()

        runBlocking {
            val call = async(start = CoroutineStart.UNDISPATCHED) { adapter.complete(AnthropicMockFixture.call()) }
            fixture.eventually { fixture.lastSeq() > before }
            call.cancel()
        }

        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    private fun complete(
        call: ChatCall = AnthropicMockFixture.call(),
        adapter: AnthropicChatAdapter = fixture.adapter(),
    ): ProviderResult = runBlocking { adapter.complete(call) }

    private fun fingerprint(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())).take(8)

    companion object {
        private const val CALLS = 200
        private const val CONCURRENCY = 16
        private val SINGLE_CONNECTION =
            AnthropicMockFixture.POOL.copy(maxConnections = 1, pendingAcquireTimeout = Duration.ofMillis(200))

        @JvmStatic
        fun injectedErrors(): List<Arguments> =
            listOf(
                Arguments.of("400", """{"status":400}""", ProviderFailure.RejectedByProvider(400, null, null)),
                Arguments.of("413", """{"status":413}""", ProviderFailure.RejectedByProvider(413, null, null)),
                Arguments.of("401", """{"status":401}""", ProviderFailure.UpstreamAuthFailed),
                Arguments.of("402", """{"status":402}""", ProviderFailure.UpstreamQuotaExhausted),
                Arguments.of("403", """{"status":403}""", ProviderFailure.UpstreamAuthFailed),
                Arguments.of("404 model", """{"status":404}""", ProviderFailure.ModelNotFound),
                Arguments.of("404 other", """{"status":404,"type":"other"}""", ProviderFailure.UpstreamError),
                Arguments.of("408", """{"status":408}""", ProviderFailure.UpstreamTimeout),
                Arguments.of(
                    "429 with retry-after",
                    """{"status":429,"retryAfterSeconds":7}""",
                    ProviderFailure.UpstreamRateLimited(7.seconds),
                ),
                Arguments.of(
                    "429 spend cap",
                    """{"status":429,"code":"enforced_spend_limit_reached"}""",
                    ProviderFailure.UpstreamQuotaExhausted,
                ),
                Arguments.of("409", """{"status":409}""", ProviderFailure.UpstreamError),
                Arguments.of("500", """{"status":500}""", ProviderFailure.UpstreamError),
                Arguments.of(
                    "500 timeout_error",
                    """{"status":500,"type":"timeout_error"}""",
                    ProviderFailure.UpstreamTimeout,
                ),
                Arguments.of(
                    "503 with retry-after-ms",
                    """{"status":503,"retryAfterMs":7000}""",
                    ProviderFailure.ProviderUnavailable(7.seconds),
                ),
                Arguments.of("504", """{"status":504}""", ProviderFailure.UpstreamTimeout),
                Arguments.of("529", """{"status":529}""", ProviderFailure.ProviderUnavailable(null)),
            )
    }
}
