package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import io.github.pdroacordi.llmgateway.gateway.app.embedding.FakeEmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactor.awaitSingle
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.client.WebClient
import reactor.blockhound.BlockingOperationError
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * BlockHound on real requests. [client] connects over a socket, so the filter and handlers run on Reactor Netty's
 * event loop. The app's `Random` stays the production DRBG; only the clock is wrapped, to see the thread
 * (ADR-FND-04-002).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(
    ProbeController::class,
    BlockHoundIntegrationTest.ThreadRecording::class,
    FakeChatModelPort.Wiring::class,
    FakeEmbeddingModelPort.Wiring::class,
)
@ExtendWith(OutputCaptureExtension::class)
class BlockHoundIntegrationTest(
    @LocalServerPort port: Int,
    @Autowired private val ids: RequestIdGenerator,
    @Autowired private val clock: ThreadRecordingClock,
    @Autowired private val provider: FakeChatModelPort,
    @Autowired private val hasher: KeyHasher,
) {
    private val client =
        WebClient
            .builder()
            .baseUrl("http://127.0.0.1:$port")
            .defaultHeader("Authorization", TestKeys.BEARER)
            .build()

    class ThreadRecordingClock(
        private val delegate: Clock,
    ) : Clock() {
        val threads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()

        override fun getZone(): ZoneId = delegate.zone

        override fun withZone(zone: ZoneId): Clock = delegate.withZone(zone)

        override fun instant(): Instant = delegate.instant()

        override fun millis(): Long {
            threads.add(Thread.currentThread())
            return delegate.millis()
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ThreadRecording {
        @Bean
        @Primary
        fun threadRecordingClock(): ThreadRecordingClock = ThreadRecordingClock(Clock.systemUTC())
    }

    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    private fun ownLog(output: CapturedOutput): String = output.all.substring(mark)

    @Test
    fun `BlockHound is installed - a blocking call on a non-blocking thread fails`() {
        val error =
            assertThrows<Throwable> {
                Mono
                    .fromCallable { Thread.sleep(1) }
                    .subscribeOn(Schedulers.parallel())
                    .block(BLOCK_LIMIT)
            }

        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is BlockingOperationError }) {
            error.stackTraceToString()
        }
    }

    @Test
    fun `a Thread sleep in a handler on the event loop gives a 500 with the BlockHound error`(output: CapturedOutput) {
        val response = get("/test-probe/blocking").block(BLOCK_LIMIT)

        assertEquals(500, response?.statusCode?.value())
        // Names the call, so a block elsewhere on the request path cannot satisfy this test. A 5xx is logged after its
        // write, so the client can have the response first.
        await()
            .atMost(Duration.ofSeconds(5))
            .until { "BlockingOperationError: Blocking call! java.lang.Thread.sleep" in ownLog(output) }
    }

    @Test
    fun `RequestIdGenerator next with the production DRBG passes BlockHound on the event loop`() {
        clock.threads.clear()

        val responses =
            Flux
                .range(0, REQUESTS)
                .flatMap({ get("/test-probe/json") }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(setOf(200), responses.map { it.statusCode.value() }.toSet())
        val requestIds = responses.mapNotNull { it.headers.getFirst(GatewayHeaders.REQUEST_ID) }.toSet()
        assertEquals(REQUESTS, requestIds.size)
        assertTrue(requestIds.all { Uuid.parse(it).toString()[VERSION_INDEX] == '7' }) { requestIds.first() }
        assertTrue(clock.threads.isNotEmpty())
        assertTrue(clock.threads.all { it.name.startsWith("webflux-http-") && Schedulers.isNonBlockingThread(it) }) {
            clock.threads.joinToString { it.name }
        }
    }

    @Test
    fun `2000 chat completions over real sockets at concurrency 32 pass BlockHound, from the first request on`() {
        val statuses =
            Flux
                .range(0, REQUESTS)
                .flatMap({ chat() }, CONCURRENCY)
                .map { it.statusCode.value() }
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(REQUESTS, statuses.size)
        assertEquals(setOf(200), statuses.toSet())
    }

    @Test
    fun `2000 embedding calls over real sockets at concurrency 32 pass BlockHound, from the first request on`() {
        val statuses =
            Flux
                .range(0, REQUESTS)
                .flatMap({ embed() }, CONCURRENCY)
                .map { it.statusCode.value() }
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(REQUESTS, statuses.size)
        assertEquals(setOf(200), statuses.toSet())
    }

    @Test
    fun `2000 streams over real sockets at concurrency 32 pass BlockHound, from the first request on`() {
        provider.reset()

        val bodies =
            Flux
                .range(0, REQUESTS)
                .flatMap({ stream().collectList() }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(REQUESTS, bodies.size)
        assertTrue(bodies.all { "[DONE]" in it.joinToString("") }) { bodies.first().toString() }
    }

    @Test
    fun `200 streams cancelled mid-flight pass BlockHound`(output: CapturedOutput) {
        provider.reset()
        provider.streamAnswer =
            {
                flow<ProviderStreamEvent> {
                    while (true) {
                        emit(FakeChatModelPort.chunk("Hi"))
                        Mono.delay(Duration.ofMillis(1)).awaitSingle()
                    }
                }
            }

        val firsts =
            Flux
                .range(0, CANCELLED)
                .flatMap({ stream().take(1).collectList() }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(CANCELLED, firsts.size)
        await()
            .atMost(Duration.ofSeconds(10))
            .until { ownLog(output).lines().count { "Stream cancelled" in it } == CANCELLED }
        assertTrue("BlockingOperationError" !in ownLog(output)) { output.all }
    }

    private fun stream(): Flux<String> =
        client
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}""")
            .exchangeToFlux { it.bodyToFlux(ByteArray::class.java).map(ByteArray::decodeToString) }

    @Test
    fun `100 000 draws from the production generator pass BlockHound on a non-blocking thread`() {
        val thread =
            Mono
                .fromCallable {
                    repeat(DRAWS) { ids.next() }
                    Thread.currentThread()
                }.subscribeOn(Schedulers.parallel())
                .block(BLOCK_LIMIT)

        assertTrue(thread != null && Schedulers.isNonBlockingThread(thread)) { thread?.name.orEmpty() }
    }

    @Test
    fun `KeyHasher digest passes BlockHound on a non-blocking thread`() {
        val thread =
            Mono
                .fromCallable {
                    repeat(DRAWS) { hasher.digest(TestKeys.TEAM_KEY) }
                    Thread.currentThread()
                }.subscribeOn(Schedulers.parallel())
                .block(BLOCK_LIMIT)

        assertTrue(thread != null && Schedulers.isNonBlockingThread(thread)) { thread?.name.orEmpty() }
    }

    private fun chat(): Mono<ResponseEntity<Void>> =
        client
            .post()
            .uri("/v1/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"temperature":0.7}""")
            .exchangeToMono { it.toBodilessEntity() }

    private fun embed(): Mono<ResponseEntity<Void>> =
        client
            .post()
            .uri("/v1/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"model":"mock-embedding","input":["Hi",""],"dimensions":3}""")
            .exchangeToMono { it.toBodilessEntity() }

    private fun get(path: String): Mono<ResponseEntity<Void>> =
        client
            .get()
            .uri(path)
            .exchangeToMono { it.toBodilessEntity() }

    private companion object {
        const val REQUESTS = 2000
        const val CONCURRENCY = 32
        const val CANCELLED = 200
        val BLOCK_LIMIT: Duration = Duration.ofMinutes(2)
        const val DRAWS = 100_000

        // The version digit in the canonical 8-4-4-4-12 text.
        const val VERSION_INDEX = 14
    }
}
