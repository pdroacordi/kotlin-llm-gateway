package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(FakeChatModelPort.Wiring::class)
@ExtendWith(OutputCaptureExtension::class)
class ChatRouteTest(
    @Autowired private val client: WebTestClient,
    @Autowired private val provider: FakeChatModelPort,
    @LocalServerPort private val port: Int,
) {
    @BeforeEach
    fun resetProvider() {
        provider.reset()
    }

    private fun post(
        body: String,
        contentType: String? = MediaType.APPLICATION_JSON_VALUE,
    ): WebTestClient.ResponseSpec =
        client
            .post()
            .uri(ROUTE)
            .headers { headers -> contentType?.let { headers.set("Content-Type", it) } }
            .bodyValue(body.encodeToByteArray())
            .exchange()

    private fun envelope(
        message: String,
        code: String?,
        param: String? = null,
        type: String = "invalid_request_error",
    ): String {
        val error = linkedMapOf("message" to message, "type" to type, "param" to param, "code" to code)
        return JSON.writeValueAsString(mapOf("error" to error))
    }

    @Test
    fun `a completion gives 200 with the gateway's headers and the provider's body`() {
        post(MINIMAL)
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("Content-Type", "application/json")
            .expectHeader()
            .exists("x-request-id")
            .expectHeader()
            .valueEquals("x-gateway-deployment", "openai")
            .expectHeader()
            .doesNotExist("x-gateway-usage-estimated")
            .expectBody()
            .json(
                """
                {"id":"chatcmpl-fake","object":"chat.completion","created":1793750400,"model":"mock-chat",
                "choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16}}
                """.trimIndent(),
                JsonCompareMode.STRICT,
            )
    }

    @Test
    fun `the port gets the request ID of the response and the model the client sent`() {
        val requestId =
            post(MINIMAL)
                .expectStatus()
                .isOk
                .returnResult(ByteArray::class.java)
                .responseHeaders
                .getFirst("x-request-id")

        val call = provider.calls.single()
        assertEquals(requestId, call.requestId.toString())
        assertEquals("mock-chat", call.deployment.model.value)
        assertEquals("openai", call.deployment.id.value)
        assertEquals("openai", call.deployment.provider.value)
    }

    @Test
    fun `omitted usage is estimated and the header says so`() {
        provider.answer = { ProviderResult.Completed(FakeChatModelPort.completion(it), null) }

        post(MINIMAL)
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("x-gateway-usage-estimated", "true")
            .expectBody()
            .jsonPath("$.usage.prompt_tokens")
            .isEqualTo(7)
            .jsonPath("$.usage.completion_tokens")
            .isEqualTo(2)
            .jsonPath("$.usage.total_tokens")
            .isEqualTo(9)
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/event-stream", "text/plain", "application/xml"])
    fun `Accept is ignored on a non-stream request`(accept: String) {
        client
            .post()
            .uri(ROUTE)
            .header("Accept", accept)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL)
            .exchange()
            .expectStatus()
            .isOk
    }

    @Test
    fun `a Content-Type with a charset is JSON`() {
        post(MINIMAL, "application/json; charset=utf-8").expectStatus().isOk
    }

    @Test
    fun `the client's Authorization does not reach the port`() {
        client
            .post()
            .uri(ROUTE)
            .header("Authorization", "Bearer client-key")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL)
            .exchange()
            .expectStatus()
            .isOk

        assertFalse("client-key" in provider.calls.single().toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["GET", "PUT", "DELETE", "PATCH", "OPTIONS"])
    fun `a method other than POST gives 405 with allow POST`(method: String) {
        client
            .method(HttpMethod.valueOf(method))
            .uri(ROUTE)
            .exchange()
            .expectStatus()
            .isEqualTo(405)
            .expectHeader()
            .valueEquals("allow", "POST")
            .expectHeader()
            .exists("x-request-id")
            .expectBody()
            .json(
                envelope("Method $method is not allowed for this URL.", "method_not_allowed"),
                JsonCompareMode.STRICT,
            )
        assertTrue(provider.calls.isEmpty())
    }

    // Spring answers a preflight before the route runs. With no CORS configuration it adds no allow header, so a
    // browser does not send the POST.
    @Test
    fun `a CORS preflight gets 200 with no allow header and no provider call`() {
        val result =
            client
                .method(HttpMethod.OPTIONS)
                .uri(ROUTE)
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .exchange()
                .expectStatus()
                .isOk
                .expectHeader()
                .exists("x-request-id")
                .returnResult(ByteArray::class.java)

        assertTrue(result.responseHeaders.headerNames().none { it.lowercase().startsWith("access-control-") })
        assertTrue(provider.calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/plain", "application/x-www-form-urlencoded", "multipart/form-data"])
    fun `a Content-Type that is not JSON gives 415`(contentType: String) {
        post(MINIMAL, contentType)
            .expectStatus()
            .isEqualTo(415)
            .expectHeader()
            .exists("x-request-id")
            .expectBody()
            .json(
                envelope("The request Content-Type is not supported by this endpoint.", "unsupported_media_type"),
                JsonCompareMode.STRICT,
            )
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `an absent Content-Type gives 415`() {
        post(MINIMAL, contentType = null).expectStatus().isEqualTo(415)
    }

    // WebTestClient refuses to send this value, so a plain client does.
    @Test
    fun `a Content-Type that cannot be parsed gives 415, not 500`() {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port$ROUTE"))
                .header("Content-Type", "not a type")
                .POST(HttpRequest.BodyPublishers.ofString(MINIMAL))
                .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(415, response.statusCode())
        assertEquals("unsupported_media_type", JSON.readTree(response.body()).at("/error/code").stringValue())
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"a":""", "", "[]", "5", "null", """{"a":1} {"b":2}""", "\"text\""])
    fun `a body that is not a JSON object gives 400 invalid_body`(body: String) {
        post(body)
            .expectStatus()
            .isEqualTo(400)
            .expectHeader()
            .exists("x-request-id")
            .expectBody()
            .json(
                envelope("The request body could not be read as JSON of the expected shape.", "invalid_body"),
                JsonCompareMode.STRICT,
            )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("overConstraints")
    fun `a body over a Jackson read constraint gives 400 invalid_body`(body: String) {
        post(body)
            .expectStatus()
            .isEqualTo(400)
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("invalid_body")
    }

    @Test
    fun `a broken body stays out of the response and the log`(output: CapturedOutput) {
        post("""{"canary-body":""")
            .expectStatus()
            .isEqualTo(400)
            .expectBody()
            .consumeWith { assertFalse("canary-body" in String(checkNotNull(it.responseBody))) }

        assertFalse("canary-body" in output.all) { output.all }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationRows")
    fun `each validation row gives its exact envelope and no provider call`(
        body: String,
        error: GatewayError.InvalidRequest,
    ) {
        post(body)
            .expectStatus()
            .isEqualTo(400)
            .expectHeader()
            .exists("x-request-id")
            .expectBody()
            .json(envelope(error.message, error.code, error.param), JsonCompareMode.STRICT)
        assertTrue(provider.calls.isEmpty())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providerFailures")
    fun `each provider failure reaches the client as its row`(failure: ProviderFailure) {
        provider.answer = { ProviderResult.Failed(failure) }

        val result = post(MINIMAL).returnResult(ByteArray::class.java)

        val requestId = RequestId(Uuid.parse(checkNotNull(result.responseHeaders.getFirst("x-request-id"))))
        val reply = failure.toReply(requestId)
        assertEquals(reply.status, result.status.value())
        assertEquals("application/json", result.responseHeaders.contentType.toString())
        assertNull(result.responseHeaders.getFirst("x-gateway-deployment"))
        reply.headers.forEach { (name, value) -> assertEquals(value, result.responseHeaders.getFirst(name), name) }
        assertEquals(
            JSON.readTree(envelope(reply.message, reply.code, reply.param, reply.type)),
            JSON.readTree(result.responseBodyContent),
        )
    }

    companion object {
        private const val ROUTE = "/v1/chat/completions"
        private const val MINIMAL = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}"""
        private const val USER = """{"role":"user","content":"Hi"}"""
        private val JSON = JsonMapper.builder().build()

        @JvmStatic
        fun overConstraints(): List<Arguments> =
            listOf(
                named("nesting", """{"a":${"[".repeat(1_100)}${"]".repeat(1_100)}}"""),
                named("number length", """{"a":${"1".repeat(1_100)}}"""),
                named("an exponent no decimal holds", """{"a":1e99999999999}"""),
            ).map { Arguments.of(it) }

        @JvmStatic
        fun validationRows(): List<Arguments> {
            val errors = GatewayError.InvalidRequest
            return listOf(
                Arguments.of("""{"messages":[$USER]}""", errors.modelRequired),
                Arguments.of("""{"model":"m"}""", errors.messagesRequired),
                Arguments.of("""{"model":"m","messages":[5]}""", errors.messageNotObject(0)),
                Arguments.of("""{"model":"m","messages":[{}]}""", errors.roleRequired(0)),
                Arguments.of("""{"model":"m","messages":[{"role":"user","content":5}]}""", errors.contentMistyped(0)),
                Arguments.of("""{"model":"m","messages":[$USER],"stream":"yes"}""", errors.streamMistyped),
                Arguments.of("""{"model":"m","messages":[$USER],"stream":true}""", errors.streamingUnavailable),
            )
        }

        @JvmStatic
        fun providerFailures(): List<ProviderFailure> =
            listOf(
                ProviderFailure.ModelNotFound,
                ProviderFailure.RejectedByProvider(400, "context_length_exceeded", "messages[0].content"),
                ProviderFailure.RejectedByProvider(422, null, null),
                ProviderFailure.UpstreamAuthFailed,
                ProviderFailure.UpstreamRateLimited(7.seconds),
                ProviderFailure.UpstreamRateLimited(null),
                ProviderFailure.UpstreamQuotaExhausted,
                ProviderFailure.ProviderUnavailable(7.seconds),
                ProviderFailure.ProviderUnavailable(null),
                ProviderFailure.UpstreamTimeout,
                ProviderFailure.UpstreamError,
            )
    }
}
