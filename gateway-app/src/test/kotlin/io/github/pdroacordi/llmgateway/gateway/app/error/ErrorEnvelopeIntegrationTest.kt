package io.github.pdroacordi.llmgateway.gateway.app.error

import com.jayway.jsonpath.JsonPath
import io.github.pdroacordi.llmgateway.gateway.app.ProbeController
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.HttpHandler
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.reactive.handler.WebFluxResponseStatusExceptionHandler
import org.springframework.web.server.WebExceptionHandler
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(ProbeController::class)
@ExtendWith(OutputCaptureExtension::class)
class ErrorEnvelopeIntegrationTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val context: ApplicationContext,
    @Autowired private val httpHandler: HttpHandler,
) {
    @Test
    fun `an unknown route gives 404 with exactly the OpenAI envelope`() {
        client
            .get()
            .uri("/v1/does-not-exist")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .json(
                """
                {"error":{"message":"Unknown request URL: GET /v1/does-not-exist.",
                "type":"invalid_request_error","param":null,"code":"unknown_url"}}
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )
        assertEnvelope(get("/v1/does-not-exist"), 404, "unknown_url")
    }

    @Test
    fun `a method the path does not allow gives 405 with an allow header`() {
        val result = get("/test-probe/echo")

        assertEnvelope(result, 405, "method_not_allowed")
        assertEquals("POST", result.responseHeaders.getFirst("allow"))
    }

    @Test
    fun `an unreadable Content-Type gives 415`() {
        val result = post("/test-probe/echo", MediaType.TEXT_PLAIN, "plain text")

        assertEnvelope(result, 415, "unsupported_media_type")
    }

    @Test
    fun `an Accept header the endpoint cannot meet gives 406`() {
        val result =
            client
                .get()
                .uri("/test-probe/json")
                .accept(MediaType.TEXT_PLAIN)
                .exchange()
                .expectBody()
                .returnResult()

        assertEnvelope(result, 406, "not_acceptable")
    }

    @Test
    fun `malformed JSON gives 400 invalid_body without echoing the body`(output: CapturedOutput) {
        val result = post("/test-probe/echo", MediaType.APPLICATION_JSON, MALFORMED)

        assertEnvelope(result, 400, "invalid_body")
        assertFalse(MALFORMED in result.bodyText()) { result.bodyText() }
        assertFalse(MALFORMED in output.all) { output.all }
    }

    @Test
    fun `a raised GatewayErrorException returns exactly its error`() {
        client
            .get()
            .uri("/test-probe/gateway-error")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .json(
                """
                {"error":{"message":"Probe message.","type":"invalid_request_error",
                "param":"probe_param","code":"probe_code"}}
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )
    }

    @Test
    fun `a bug gives 500 with the request ID, no exception message, and one ERROR line`(output: CapturedOutput) {
        val result = get("/test-probe/boom?$QUERY")
        val requestId = result.requestId()

        assertEnvelope(result, 500, "internal_error", type = "server_error")
        assertTrue(requestId in message(result)) { message(result) }
        assertFalse(ProbeController.SECRET in result.bodyText()) { result.bodyText() }
        // A 5xx is logged after its write, so the client can have the response first.
        await()
            .atMost(Duration.ofSeconds(5))
            .until { output.all.lines().any { " ERROR " in it && "[$requestId]" in it } }
        val errorLines = output.all.lines().filter { " ERROR " in it && "[$requestId]" in it }
        assertEquals(1, errorLines.size) { output.all }
        val stackTrace =
            output.all
                .substringAfter(errorLines.single())
                .trimStart()
                .lineSequence()
                .first()
        assertTrue("java.lang.IllegalStateException: ${ProbeController.SECRET}" in stackTrace) { stackTrace }
        // Spring's checkpoint names the raw query string; it must not reach the log.
        assertFalse(QUERY in output.all) { output.all }
    }

    @Test
    fun `the context holds exactly one ErrorWebExceptionHandler, the gateway's`() {
        val handlers = context.getBeansOfType(ErrorWebExceptionHandler::class.java).values

        assertEquals(1, handlers.size) { handlers.toString() }
        assertTrue(handlers.single() is GatewayErrorWebExceptionHandler) { handlers.toString() }
    }

    // Catches what ARCH-10's name list cannot see, such as a subclass of a base class declared in `.error`.
    @Test
    fun `the only WebExceptionHandlers are the gateway's and Spring's status handler, and no advice exists`() {
        val handlers = context.getBeansOfType(WebExceptionHandler::class.java).values.map { it::class.java }

        assertEquals(
            setOf(GatewayErrorWebExceptionHandler::class.java, WebFluxResponseStatusExceptionHandler::class.java),
            handlers.toSet(),
        )
        assertEquals(2, handlers.size) { handlers.toString() }
        assertTrue(context.getBeanNamesForAnnotation(ControllerAdvice::class.java).isEmpty())
    }

    @Test
    fun `an error after commit gives one ERROR line, with the request ID and no query string`(output: CapturedOutput) {
        val request = MockServerHttpRequest.get("/test-probe/committed?$QUERY").build()
        val response = MockServerHttpResponse()

        runCatching { httpHandler.handle(request, response).block() }

        val requestId = checkNotNull(response.headers.getFirst(GatewayHeaders.REQUEST_ID))
        // Log lines start at column 0; stack frames, which can quote this test's name, are indented.
        val errorLines = output.all.lines().filter { it.trimStart() == it && " ERROR " in it && "[$requestId]" in it }
        assertEquals(1, errorLines.size) { output.all }
        assertFalse(QUERY in output.all) { output.all }
    }

    private fun get(uri: String): EntityExchangeResult<ByteArray> =
        client
            .get()
            .uri(uri)
            .exchange()
            .expectBody()
            .returnResult()

    private fun post(
        uri: String,
        contentType: MediaType,
        body: String,
    ): EntityExchangeResult<ByteArray> =
        client
            .post()
            .uri(uri)
            .contentType(contentType)
            .bodyValue(body)
            .exchange()
            .expectBody()
            .returnResult()

    // Exactly the four OpenAI members, application/json, a request ID, and none of Boot's default members.
    private fun assertEnvelope(
        result: EntityExchangeResult<ByteArray>,
        status: Int,
        code: String,
        type: String = "invalid_request_error",
    ) {
        assertEquals(status, result.status.value())
        assertEquals(MediaType.APPLICATION_JSON, result.responseHeaders.contentType)
        assertTrue(UUID_V7.matches(result.requestId())) { result.requestId() }
        val body = JsonPath.parse(result.bodyText())
        assertEquals(setOf("error"), body.read<Map<String, Any?>>("$").keys)
        val error = body.read<Map<String, Any?>>("$.error")
        assertEquals(setOf("message", "type", "param", "code"), error.keys)
        assertEquals(type, error["type"])
        assertEquals(code, error["code"])
    }

    private fun message(result: EntityExchangeResult<ByteArray>): String =
        JsonPath.parse(result.bodyText()).read("$.error.message")

    private fun EntityExchangeResult<ByteArray>.requestId(): String =
        checkNotNull(responseHeaders.getFirst(GatewayHeaders.REQUEST_ID)) { "no x-request-id on $url" }

    private fun EntityExchangeResult<ByteArray>.bodyText(): String = responseBody?.decodeToString().orEmpty()

    private companion object {
        const val MALFORMED = """{"a":"""
        const val QUERY = "secret-query=value-from-the-client"
        val UUID_V7 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}
