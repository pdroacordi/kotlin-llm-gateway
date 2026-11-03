package io.github.pdroacordi.llmgateway.gateway.app

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.ApplicationContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayApplicationTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val context: ApplicationContext,
) {
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
    fun `the test-only probe controller is not component-scanned`() {
        val probes = context.getBeanNamesForType(ProbeController::class.java)

        assertTrue(probes.isEmpty()) { probes.joinToString() }
    }
}
