package io.github.pdroacordi.llmgateway.gateway.app

import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestIdGenerator
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
@Import(ProbeController::class, BlockHoundIntegrationTest.ThreadRecording::class)
@ExtendWith(OutputCaptureExtension::class)
class BlockHoundIntegrationTest(
    @LocalServerPort port: Int,
    @Autowired private val ids: RequestIdGenerator,
    @Autowired private val clock: ThreadRecordingClock,
) {
    private val client = WebClient.create("http://127.0.0.1:$port")

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

    @Test
    fun `BlockHound is installed - a blocking call on a non-blocking thread fails`() {
        val error =
            assertThrows<Throwable> {
                Mono
                    .fromCallable { Thread.sleep(1) }
                    .subscribeOn(Schedulers.parallel())
                    .block()
            }

        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is BlockingOperationError }) {
            error.stackTraceToString()
        }
    }

    @Test
    fun `a Thread sleep in a handler on the event loop gives a 500 with the BlockHound error`(output: CapturedOutput) {
        val response = get("/test-probe/blocking").block()

        assertEquals(500, response?.statusCode?.value())
        // Names the call, so a block elsewhere on the request path cannot satisfy this test. A 5xx is logged after its
        // write, so the client can have the response first.
        await()
            .atMost(Duration.ofSeconds(5))
            .until { "BlockingOperationError: Blocking call! java.lang.Thread.sleep" in output.all }
    }

    @Test
    fun `RequestIdGenerator next with the production DRBG passes BlockHound on the event loop`() {
        clock.threads.clear()

        val responses =
            Flux
                .range(0, REQUESTS)
                .flatMap({ get("/test-probe/json") }, CONCURRENCY)
                .collectList()
                .block()
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
    fun `100 000 draws from the production generator pass BlockHound on a non-blocking thread`() {
        val thread =
            Mono
                .fromCallable {
                    repeat(DRAWS) { ids.next() }
                    Thread.currentThread()
                }.subscribeOn(Schedulers.parallel())
                .block()

        assertTrue(thread != null && Schedulers.isNonBlockingThread(thread)) { thread?.name.orEmpty() }
    }

    private fun get(path: String): Mono<ResponseEntity<Void>> =
        client
            .get()
            .uri(path)
            .exchangeToMono { it.toBodilessEntity() }

    private companion object {
        const val REQUESTS = 2000
        const val CONCURRENCY = 32
        const val DRAWS = 100_000

        // The version digit in the canonical 8-4-4-4-12 text.
        const val VERSION_INDEX = 14
    }
}
