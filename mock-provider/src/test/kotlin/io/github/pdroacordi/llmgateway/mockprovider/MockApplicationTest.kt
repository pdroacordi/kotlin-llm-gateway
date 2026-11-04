package io.github.pdroacordi.llmgateway.mockprovider

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.core.env.Environment
import org.springframework.test.web.reactive.server.WebTestClient
import reactor.blockhound.BlockingOperationError
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MockApplicationTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val environment: Environment,
) {
    // The journal keeps prompts, so a process on a developer machine stays off the network; the image sets 0.0.0.0.
    @Test
    fun `the default bind address is loopback`() {
        assertEquals("127.0.0.1", environment.getProperty("server.address"))
    }

    @Test
    fun `actuator health reports UP`() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
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
}
