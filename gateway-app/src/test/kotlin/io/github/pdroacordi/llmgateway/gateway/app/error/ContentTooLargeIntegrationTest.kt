package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.app.ProbeController
import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.http.codecs.max-in-memory-size=1KB"],
)
@AutoConfigureWebTestClient
@Import(ProbeController::class, FakeChatModelPort.Wiring::class)
@ExtendWith(OutputCaptureExtension::class)
class ContentTooLargeIntegrationTest(
    @Autowired unauthenticated: WebTestClient,
    @Autowired private val provider: FakeChatModelPort,
) {
    private val client = unauthenticated.mutate().defaultHeader("Authorization", TestKeys.BEARER).build()

    @Test
    fun `a body over the codec limit gives 413 request_too_large`() {
        client
            .post()
            .uri("/test-probe/echo")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"a":"${"x".repeat(2048)}"}""")
            .exchange()
            .expectStatus()
            .isEqualTo(413)
            .expectHeader()
            .contentType(MediaType.APPLICATION_JSON)
            .expectHeader()
            .exists("x-request-id")
            .expectBody()
            .json(
                """
                {"error":{"message":"The request body is larger than this endpoint accepts.",
                "type":"invalid_request_error","param":null,"code":"request_too_large"}}
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )
    }

    // The functional route reads its body itself, so Spring's translation does not apply
    // (docs/knowledge/bugs/gateway-body-limit-on-functional-route-gives-500.md).
    @Test
    fun `a body over the limit on the functional route gives 413 and no ERROR line`(output: CapturedOutput) {
        val result =
            client
                .post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"model":"m","messages":[{"role":"user","content":"${"x".repeat(2048)}"}]}""")
                .exchange()
                .expectStatus()
                .isEqualTo(413)
                .expectHeader()
                .contentType(MediaType.APPLICATION_JSON)
                .expectHeader()
                .exists("x-request-id")
                .expectBody()
                .json(
                    """
                    {"error":{"message":"The request body is larger than this endpoint accepts.",
                    "type":"invalid_request_error","param":null,"code":"request_too_large"}}
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                ).returnResult()

        assertTrue(provider.calls.isEmpty())
        val requestId = result.responseHeaders.getFirst("x-request-id").orEmpty()
        // Long enough for a 5xx line, which is written after the response.
        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(2)).until { true }
        assertFalse(output.all.lines().any { " ERROR " in it && requestId in it }) { output.all }
    }
}
