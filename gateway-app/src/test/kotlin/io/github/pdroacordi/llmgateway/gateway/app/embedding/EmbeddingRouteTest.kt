package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.config.OpenAiProperties
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.toReply
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(FakeEmbeddingModelPort.Wiring::class)
@ExtendWith(OutputCaptureExtension::class)
class EmbeddingRouteTest(
    @Autowired private val unauthenticated: WebTestClient,
    @Autowired private val provider: FakeEmbeddingModelPort,
    @Autowired private val properties: OpenAiProperties,
) {
    private val client = unauthenticated.mutate().defaultHeader("Authorization", TestKeys.BEARER).build()

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
    fun `application yaml holds the defaults of GW-05 section 5`() {
        val embeddings = properties.embeddings

        assertNull(embeddings.maxInFlightBytes)
        assertEquals(3072, embeddings.defaultDimensions)
        assertEquals(4, embeddings.cpuThreads)
        assertEquals(256, embeddings.cpuQueueSize)
        assertEquals(Duration.ofSeconds(5), embeddings.busyRetryAfter)
    }

    @Test
    fun `a lane too busy to take the provider's reply gives 503 gateway_overloaded`() {
        provider.answer = { EmbeddingResult.Busy }

        post(MINIMAL)
            .expectStatus()
            .isEqualTo(503)
            .expectHeader()
            .exists("retry-after")
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("gateway_overloaded")
    }

    @Test
    fun `an answer gives 200 with the gateway's headers and the vector byte for byte`() {
        val body =
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
                .expectBody(ByteArray::class.java)
                .returnResult()
                .responseBody

        assertEquals(
            """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[ 1e-5, 0.10 ]}],""" +
                """"model":"mock-embedding","usage":{"prompt_tokens":2,"total_tokens":2}}""",
            checkNotNull(body).decodeToString(),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [""""a"""", """["a","b"]""", "[1,2]", "[[1],[2]]"])
    fun `each input form gives 200`(input: String) {
        post("""{"model":"mock-embedding","input":$input}""").expectStatus().isOk
    }

    @Test
    fun `the port gets the request ID of the response, the team, the model, the input and the extras`() {
        val requestId =
            post("""{"model":"mock-embedding","input":"a","dimensions":3,"encoding_format":"base64"}""")
                .expectStatus()
                .isOk
                .returnResult(ByteArray::class.java)
                .responseHeaders
                .getFirst("x-request-id")

        val call = provider.calls.single()
        assertEquals(requestId, call.requestId.toString())
        assertEquals(TestKeys.TEAM_ID, call.team)
        assertEquals("mock-embedding", call.deployment.model.value)
        assertEquals("a", (call.input as EmbeddingInput.Text).text)
        assertEquals(
            mapOf("dimensions" to JsonValue.Number("3"), "encoding_format" to JsonValue.Text("base64")),
            call.extras.members,
        )
    }

    @Test
    fun `omitted usage is estimated, and the header and the body say so`() {
        provider.answer = { EmbeddingResult.Embedded(FakeEmbeddingModelPort.list(it), null) }

        post("""{"model":"mock-embedding","input":"12345"}""")
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals("x-gateway-usage-estimated", "true")
            .expectBody()
            .jsonPath("$.usage.prompt_tokens")
            .isEqualTo(2)
            .jsonPath("$.usage.total_tokens")
            .isEqualTo(2)
            .jsonPath("$.usage.completion_tokens")
            .doesNotExist()
            .jsonPath("$.llmgw.usage_estimated")
            .isEqualTo(true)
    }

    @Test
    fun `no key gives 401 and no provider call`() {
        unauthenticated
            .post()
            .uri(ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("missing_api_key")

        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `an unknown key gives 401 and no provider call`() {
        unauthenticated
            .post()
            .uri(ROUTE)
            .header("Authorization", "Bearer ${TestKeys.UNKNOWN_KEY}")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isUnauthorized
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("invalid_api_key")

        assertTrue(provider.calls.isEmpty())
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

    @Test
    fun `a body over the limit gives 413 and no provider call`() {
        val padding = "a".repeat(OVER_BODY_LIMIT)

        post("""{"model":"mock-embedding","input":"$padding"}""")
            .expectStatus()
            .isEqualTo(413)
            .expectBody()
            .jsonPath("$.error.code")
            .isEqualTo("request_too_large")
        assertTrue(provider.calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/plain", "application/x-www-form-urlencoded"])
    fun `a Content-Type that is not JSON gives 415`(contentType: String) {
        post(MINIMAL, contentType).expectStatus().isEqualTo(415)
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `an absent Content-Type gives 415`() {
        post(MINIMAL, contentType = null).expectStatus().isEqualTo(415)
    }

    @ParameterizedTest
    @ValueSource(strings = ["text/event-stream", "text/plain", "application/xml"])
    fun `Accept is ignored`(accept: String) {
        client
            .post()
            .uri(ROUTE)
            .header("Accept", accept)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(MINIMAL.encodeToByteArray())
            .exchange()
            .expectStatus()
            .isOk
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"a":""", "", "[]", "5", "null"])
    fun `a body that is not a JSON object gives 400 invalid_body`(body: String) {
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
    fun `each gateway row gives its exact envelope and no provider call`(
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
        provider.answer = { EmbeddingResult.Failed(failure) }

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
        private const val ROUTE = "/v1/embeddings"
        private const val MINIMAL = """{"model":"mock-embedding","input":"a"}"""
        private const val OVER_BODY_LIMIT = 10 * 1024 * 1024 + 1
        private val JSON = JsonMapper.builder().build()

        @JvmStatic
        fun validationRows(): List<Arguments> {
            val errors = GatewayError.InvalidRequest
            return listOf(
                Arguments.of("""{"input":"a"}""", errors.modelRequired),
                Arguments.of("""{"model":"","input":"a"}""", errors.modelRequired),
                Arguments.of("""{"model":5,"input":"a"}""", errors.modelRequired),
                Arguments.of("""{"model":"m"}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":null}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":5}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":true}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":{}}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":["a",1]}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":[1.5]}""", errors.inputRequired),
                Arguments.of("""{"model":"m","input":[9223372036854775808]}""", errors.inputRequired),
            )
        }

        @JvmStatic
        fun providerFailures(): List<ProviderFailure> =
            listOf(
                ProviderFailure.ModelNotFound,
                ProviderFailure.RejectedByProvider(400, "invalid_value", "dimensions"),
                ProviderFailure.RejectedByProvider(422, null, null),
                ProviderFailure.UnsupportedByProvider("unsupported_value", "model"),
                ProviderFailure.UpstreamAuthFailed,
                ProviderFailure.UpstreamRateLimited(7.seconds),
                ProviderFailure.UpstreamQuotaExhausted,
                ProviderFailure.ProviderUnavailable(null),
                ProviderFailure.UpstreamTimeout,
                ProviderFailure.UpstreamError,
            )
    }
}
