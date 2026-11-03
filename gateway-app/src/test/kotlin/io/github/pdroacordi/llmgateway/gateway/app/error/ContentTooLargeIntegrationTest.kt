package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.app.ProbeController
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.http.codecs.max-in-memory-size=1KB"],
)
@AutoConfigureWebTestClient
@Import(ProbeController::class)
class ContentTooLargeIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
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
}
