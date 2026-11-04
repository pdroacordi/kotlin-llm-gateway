package io.github.pdroacordi.llmgateway.gateway.app.request

import io.github.pdroacordi.llmgateway.gateway.app.ProbeController
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.util.JsonPathExpectationsHelper
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(ProbeController::class)
@ExtendWith(OutputCaptureExtension::class)
class RequestContextIntegrationTest(
    @Autowired private val unauthenticated: WebTestClient,
) {
    private val client = unauthenticated.mutate().defaultHeader("Authorization", TestKeys.BEARER).build()

    @Test
    fun `actuator health carries a UUIDv7 request ID`() {
        val result = exchange("/actuator/health")

        assertEquals(200, result.status.value())
        assertUuidV7(result.requestId())
    }

    @Test
    fun `an unknown route carries a UUIDv7 request ID`() {
        val result = exchange("/v1/does-not-exist")

        assertEquals(404, result.status.value())
        assertUuidV7(result.requestId())
    }

    @Test
    fun `two requests get different IDs`() {
        assertNotEquals(exchange("/actuator/health").requestId(), exchange("/actuator/health").requestId())
    }

    @Test
    fun `a request's own x-request-id is ignored`() {
        val result = exchange("/actuator/health", GatewayHeaders.REQUEST_ID to listOf("client-chosen"))

        assertNotEquals("client-chosen", result.requestId())
        assertUuidV7(result.requestId())
    }

    @Test
    fun `a valid x-client-request-id is echoed`() {
        val result = exchange("/actuator/health", GatewayHeaders.CLIENT_REQUEST_ID to listOf("abc-123"))

        assertEquals(200, result.status.value())
        assertEquals("abc-123", result.responseHeaders.getFirst(GatewayHeaders.CLIENT_REQUEST_ID))
    }

    @Test
    fun `an empty x-client-request-id counts as absent`() {
        val result = exchange("/actuator/health", GatewayHeaders.CLIENT_REQUEST_ID to listOf(""))

        assertEquals(200, result.status.value())
        assertNull(result.responseHeaders.getFirst(GatewayHeaders.CLIENT_REQUEST_ID))
    }

    @Test
    fun `a 513-character x-client-request-id gives 400 from the filter, with a request ID`() {
        val result = exchange("/actuator/health", GatewayHeaders.CLIENT_REQUEST_ID to listOf("x".repeat(513)))

        assertInvalidClientRequestId(result)
    }

    @Test
    fun `a repeated x-client-request-id gives 400`() {
        val result = exchange("/actuator/health", GatewayHeaders.CLIENT_REQUEST_ID to listOf("one", "two"))

        assertInvalidClientRequestId(result)
    }

    @Test
    fun `a suspend handler reads the request ID from the coroutine context and MDC after a dispatcher hop`(
        output: CapturedOutput,
    ) {
        val result = exchange("/test-probe/context")
        val requestId = result.requestId()
        val body = result.bodyText()

        assertEquals(200, result.status.value())
        JsonPathExpectationsHelper("$.fromCoroutine").assertValue(body, requestId)
        JsonPathExpectationsHelper("$.fromMdc").assertValue(body, requestId)
        val lines = output.all.lines().filter { ProbeController.CONTEXT_LOG_LINE in it && "[$requestId]" in it }
        assertEquals(1, lines.size) { output.all }
    }

    private fun assertInvalidClientRequestId(result: EntityExchangeResult<ByteArray>) {
        assertEquals(400, result.status.value())
        assertUuidV7(result.requestId())
        JsonPathExpectationsHelper("$.error.code").assertValue(result.bodyText(), "invalid_client_request_id")
    }

    @Test
    fun `a 401 from the auth filter carries a gateway x-request-id`() {
        val result =
            unauthenticated
                .get()
                .uri("/v1/chat/completions")
                .exchange()
                .expectBody()
                .returnResult()

        assertEquals(401, result.status.value())
        assertUuidV7(result.requestId())
    }

    private fun exchange(
        uri: String,
        vararg headers: Pair<String, List<String>>,
    ): EntityExchangeResult<ByteArray> =
        client
            .get()
            .uri(uri)
            .headers { target -> headers.forEach { (name, values) -> target.addAll(name, values) } }
            .exchange()
            .expectBody()
            .returnResult()

    private fun EntityExchangeResult<ByteArray>.requestId(): String =
        checkNotNull(responseHeaders.getFirst(GatewayHeaders.REQUEST_ID)) { "no x-request-id on $url" }

    private fun EntityExchangeResult<ByteArray>.bodyText(): String = responseBody?.decodeToString().orEmpty()

    private fun assertUuidV7(value: String) {
        assertTrue(UUID_V7.matches(value)) { value }
    }

    private companion object {
        val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
