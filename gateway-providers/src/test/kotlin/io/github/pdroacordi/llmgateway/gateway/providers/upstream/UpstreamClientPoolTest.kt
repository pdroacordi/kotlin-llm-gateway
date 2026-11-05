package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a full pool raises, read from the exception and not inferred from a count (run 2026-11-05, R2). */
@Timeout(30)
class UpstreamClientPoolTest {
    private val release = CountDownLatch(1)
    private val served = AtomicInteger()

    private val provider: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/held") { exchange ->
                exchange.requestBody.readAllBytes()
                served.incrementAndGet()
                release.await(HOLD_SECONDS, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }

    private val client =
        UpstreamClient(
            "pool-test",
            Duration.ofSeconds(5),
            1024,
            PoolConfig(1, Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofMinutes(5)),
        )

    @AfterEach
    fun stop() {
        release.countDown()
        client.close()
        provider.stop(0)
    }

    // Spring starts coroutine handlers Unconfined, so the client's callers run as these do.
    @Suppress("InjectDispatcher")
    @Test
    fun `past three calls for each connection the pool raises its pending limit, wrapped by WebClient`() {
        val endpoint = URI.create("http://127.0.0.1:${provider.address.port}/held")
        val broken = AtomicInteger()

        val exchanges =
            runBlocking {
                val calls =
                    List(CALLS) {
                        async(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
                            client.exchange(endpoint, CALL_LIMIT, {}, ByteArray(0)).also {
                                if (it is Exchange.Broken) broken.incrementAndGet()
                            }
                        }
                    }
                withTimeout(WAIT_LIMIT_MS) { while (broken.get() < CALLS - ADMITTED) delay(POLL_MS) }
                release.countDown()
                withTimeout(WAIT_LIMIT_MS) { calls.awaitAll() }
            }

        val failures = exchanges.filterIsInstance<Exchange.Broken>().map { it.failure }
        assertEquals(CALLS - ADMITTED, failures.size) { failures.toString() }
        assertEquals(ADMITTED, served.get())
        failures.forEach { failure ->
            val chain = generateSequence(failure) { it.cause }.toList()
            assertTrue(chain.any { it is PoolAcquirePendingLimitException }) { chain.toString() }
        }
    }

    private companion object {
        const val CALLS = 6

        // One connection, and two waiting for it: UpstreamClient's PENDING_PER_CONNECTION.
        const val ADMITTED = 3
        const val HOLD_SECONDS = 20L
        const val WAIT_LIMIT_MS = 10_000L
        const val POLL_MS = 10L
        val CALL_LIMIT: Duration = Duration.ofSeconds(20)
    }
}
