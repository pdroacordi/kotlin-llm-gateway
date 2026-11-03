package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

/**
 * Code after a mock wait runs on a thread BlockHound watches, not kotlinx's DefaultExecutor
 * (docs/knowledge/bugs/mock-serving-after-delay-runs-on-default-executor.md). The probe routes use the same wait and
 * the same coRouter context as `Serving`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(ServingThreadIntegrationTest.ThreadProbes::class)
class ServingThreadIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class ThreadProbes {
        @Bean
        fun threadProbeRoutes(): RouterFunction<ServerResponse> =
            coRouter {
                GET("/v1/test-thread-after-wait") {
                    awaitDelay(WAIT_MS)
                    ServerResponse.ok().bodyValueAndAwait(Thread.currentThread().name)
                }
                GET("/v1/test-blocking-after-wait") {
                    awaitDelay(WAIT_MS)
                    @Suppress("SleepInsteadOfDelay") // The deliberate blocking call BlockHound must catch.
                    Thread.sleep(1)
                    ServerResponse.ok().bodyValueAndAwait("not caught")
                }
            }
    }

    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `a handler resumes on a Reactor parallel thread after the wait`() {
        val thread =
            client
                .get()
                .uri("/v1/test-thread-after-wait")
                .exchange()
                .expectBody()
                .returnResult()
                .bodyText()

        assertTrue(thread.startsWith("parallel-")) { thread }
        assertFalse("DefaultExecutor" in thread) { thread }
    }

    @Test
    fun `BlockHound catches a blocking call made after the wait`() {
        val status =
            client
                .get()
                .uri("/v1/test-blocking-after-wait")
                .exchange()
                .expectBody()
                .returnResult()
                .status

        assertEquals(500, status.value())
    }

    @Test
    fun `a reply after scenario latency is still served`() {
        client.putScenario("mock-chat", """{"otherwise":{"latency":{"ttftMs":30,"perTokenMs":5}}}""")

        assertEquals(200, client.chat(chatBody("x")).status.value())
        assertEquals(200, client.chat(chatBody("x", extra = ""","stream":true""")).status.value())
    }

    private companion object {
        const val WAIT_MS = 20L
    }
}
