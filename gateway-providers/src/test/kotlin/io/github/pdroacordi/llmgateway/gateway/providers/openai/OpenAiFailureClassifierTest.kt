package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class OpenAiFailureClassifierTest {
    private val classifier = OpenAiFailureClassifier(JsonMapper.builder().build().exactTreeReader())

    private fun classify(
        status: Int,
        body: String = "",
        headers: HttpHeaders = HttpHeaders(),
    ): ProviderFailure = classifier.classify(status, headers, body.encodeToByteArray(), NOW)

    private fun error(
        code: String? = null,
        type: String = "invalid_request_error",
        param: String? = null,
    ): String {
        val quotedCode = code?.let { "\"$it\"" } ?: "null"
        val quotedParam = param?.let { "\"$it\"" } ?: "null"
        return """{"error":{"message":"$CANARY","type":"$type","param":$quotedParam,"code":$quotedCode}}"""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `each upstream condition maps to its failure`(
        name: String,
        status: Int,
        body: String,
        expected: ProviderFailure,
    ) {
        assertEquals(expected, classify(status, body), name)
    }

    @Test
    fun `a 404 for a model is not a wrong base URL`() {
        assertEquals(ProviderFailure.ModelNotFound, classify(404, error("model_not_found")))
        assertEquals(ProviderFailure.UpstreamError, classify(404, error("something_else")))
        assertEquals(ProviderFailure.UpstreamError, classify(404, "<html>nginx</html>"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "insufficient_quota",
            "credit_balance_exhausted",
            "organization_spend_limit_exceeded",
            "project_spend_limit_exceeded",
            "organization_usage_limit_exceeded",
        ],
    )
    fun `the quota set matches in code or in type`(value: String) {
        assertEquals(ProviderFailure.UpstreamQuotaExhausted, classify(429, error(code = value)))
        assertEquals(ProviderFailure.UpstreamQuotaExhausted, classify(429, error(type = value)))
    }

    @Test
    fun `any other 429 is a rate limit and keeps the wait`() {
        val headers = HttpHeaders().apply { add("retry-after", "7") }

        assertEquals(
            ProviderFailure.UpstreamRateLimited(7.seconds),
            classify(429, error("rate_limit_exceeded"), headers),
        )
    }

    @Test
    fun `a 503 keeps the wait`() {
        val headers = HttpHeaders().apply { add("retry-after-ms", "7000") }

        assertEquals(ProviderFailure.ProviderUnavailable(7.seconds), classify(503, "", headers))
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 413, 422])
    fun `a relayed rejection keeps the status, code and param`(status: Int) {
        assertEquals(
            ProviderFailure.RejectedByProvider(status, "context_length_exceeded", "messages[0].content"),
            classify(status, error("context_length_exceeded", param = "messages[0].content")),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "Has Capitals",
            "has space",
            "org-1 quoted",
            "a\\nb",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        ],
    )
    fun `a code that could carry text becomes null`(code: String) {
        assertEquals(ProviderFailure.RejectedByProvider(400, null, null), classify(400, error(code)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["has space", "a\\\"b", "messages[0](x)"])
    fun `a param that could carry text becomes null`(param: String) {
        assertEquals(
            ProviderFailure.RejectedByProvider(400, "bad", null),
            classify(400, error("bad", param = param)),
        )
    }

    @Test
    fun `a param of 129 characters becomes null and one of 128 passes`() {
        val longest = "a".repeat(128)

        assertEquals(
            ProviderFailure.RejectedByProvider(400, null, longest),
            classify(400, error(param = longest)),
        )
        assertEquals(
            ProviderFailure.RejectedByProvider(400, null, null),
            classify(400, error(param = longest + "a")),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "<html><body>bad gateway</body></html>",
            "[1,2]",
            "null",
            """{"error":"text"}""",
            """{"error":{"code":5}}""",
            """{"error":""",
            """{"error":1e99999999999}""",
        ],
    )
    fun `a body that is not the error shape is tolerated`(body: String) {
        assertEquals(ProviderFailure.RejectedByProvider(400, null, null), classify(400, body))
        assertEquals(ProviderFailure.UpstreamError, classify(500, body))
        assertEquals(ProviderFailure.UpstreamRateLimited(null), classify(429, body))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyFailure")
    fun `no result contains the provider's message`(
        name: String,
        status: Int,
        body: String,
        expected: ProviderFailure,
    ) {
        assertFalse(CANARY in classify(status, body).toString(), name)
        assertEquals(expected::class, classify(status, body)::class)
    }

    @ParameterizedTest
    @CsvSource("rate_limit_exceeded,requests,rate_limit_exceeded,requests")
    fun `error fields are the code and type that pass the check`(
        code: String,
        type: String,
        loggedCode: String,
        loggedType: String,
    ) {
        val fields = classifier.errorFields(error(code, type).encodeToByteArray())

        assertEquals(loggedCode, fields.code)
        assertEquals(loggedType, fields.type)
    }

    @Test
    fun `error fields never carry free text`() {
        val fields = classifier.errorFields(error("Free Text", "Free Text").encodeToByteArray())

        assertNull(fields.code)
        assertNull(fields.type)
    }

    companion object {
        private const val CANARY = "canary-provider-message org-canary"
        private val NOW: Instant = Instant.parse("2026-11-03T12:00:00Z")

        private fun body(
            code: String,
            type: String = "x",
        ): String = """{"error":{"message":"$CANARY","type":"$type","param":null,"code":"$code"}}"""

        @JvmStatic
        fun rows(): List<Arguments> =
            listOf(
                Arguments.of("401", 401, body("invalid_api_key"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of("403", 403, body("x"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of("408", 408, "", ProviderFailure.UpstreamTimeout),
                Arguments.of("504", 504, "", ProviderFailure.UpstreamTimeout),
                Arguments.of("503", 503, "", ProviderFailure.ProviderUnavailable(null)),
                Arguments.of("500", 500, body("x"), ProviderFailure.UpstreamError),
                Arguments.of("502", 502, "", ProviderFailure.UpstreamError),
                Arguments.of("409", 409, body("x"), ProviderFailure.UpstreamError),
                Arguments.of("302", 302, "", ProviderFailure.UpstreamError),
                Arguments.of("529", 529, "", ProviderFailure.UpstreamError),
                Arguments.of("404 model", 404, body("model_not_found"), ProviderFailure.ModelNotFound),
            )

        @JvmStatic
        fun everyFailure(): List<Arguments> =
            rows() +
                listOf(
                    Arguments.of(
                        "400",
                        400,
                        body("bad"),
                        ProviderFailure.RejectedByProvider(400, "bad", null),
                    ),
                    Arguments.of("429", 429, body("x"), ProviderFailure.UpstreamRateLimited(null)),
                    Arguments.of("429 quota", 429, body("insufficient_quota"), ProviderFailure.UpstreamQuotaExhausted),
                )
    }
}
