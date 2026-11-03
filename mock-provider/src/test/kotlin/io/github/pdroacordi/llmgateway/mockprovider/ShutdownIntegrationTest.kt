package io.github.pdroacordi.llmgateway.mockprovider

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.Disposable
import java.time.Duration

/**
 * `server.shutdown: immediate`, a zero event-loop quiet period and a 3 s Netty lifecycle timeout (application.yaml,
 * ServerConfiguration): closing the context, as the JVM's shutdown hook does on a stop signal, does not wait for a
 * request held by a `hang` fault beyond that timeout. Graceful shutdown would wait out the hold (mock.max-hold).
 */
class ShutdownIntegrationTest {
    @Test
    fun `immediate shutdown stops within 4 s with a request in a hang fault`() {
        val elapsed = closeWithHangingRequest()

        // The held connection is waited for until the 3 s lifecycle timeout; the event loops then stop at once.
        assertTrue(elapsed < 4000) { "close took $elapsed ms" }
    }

    private fun closeWithHangingRequest(vararg properties: String): Long {
        val context = start(*properties)
        var request: Disposable? = null
        try {
            val client = WebClient.create("http://localhost:${context.environment.getProperty("local.server.port")}")
            client
                .put()
                .uri("/__mock/scenarios/{model}", "held")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"otherwise":{"fault":{"kind":"hang"}}}""")
                .retrieve()
                .toBodilessEntity()
                .block(TIMEOUT)
            request =
                client
                    .post()
                    .uri("/v1/chat/completions")
                    .header("authorization", "Bearer sk-test-shutdown")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"model":"held","messages":[{"role":"user","content":"wait"}]}""")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .subscribe({}, {})
            awaitInFlight(client)
            val start = System.nanoTime()
            context.close()
            return (System.nanoTime() - start) / NANOS_PER_MS
        } finally {
            request?.dispose()
            context.close()
        }
    }

    private fun start(vararg properties: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(MockProviderApplication::class.java)
            .properties("server.port=0", "mock.max-hold=60s", *properties)
            .run()

    private fun awaitInFlight(client: WebClient) {
        val deadline = System.nanoTime() + TIMEOUT.toNanos()
        while (true) {
            val stats =
                client
                    .get()
                    .uri(
                        "/__mock/stats",
                    ).retrieve()
                    .bodyToMono(String::class.java)
                    .block(TIMEOUT)
                    .orEmpty()
            if ("\"inFlight\":1" in stats) return
            check(System.nanoTime() < deadline) { "the held request never arrived: $stats" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)
        const val NANOS_PER_MS = 1_000_000L
        const val POLL_MS = 20L
    }
}
