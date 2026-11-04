package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

internal class AnthropicFailureClassifierTest {
    private val classifier = AnthropicFailureClassifier(JsonMapper.builder().build().exactTreeReader())

    private fun body(
        type: String,
        code: String? = null,
    ): String {
        val details = code?.let { ""","details":{"error_code":"$it"}""" }.orEmpty()
        return """{"type":"error","error":{"type":"$type","message":"secret text"$details},"request_id":"r"}"""
    }

    private fun classify(
        status: Int,
        body: String,
        headers: HttpHeaders = HttpHeaders(),
    ): ProviderFailure = classifier.classify(status, headers, body.encodeToByteArray(), NOW)

    @ParameterizedTest
    @MethodSource("statuses")
    fun `the status table holds`(
        status: Int,
        body: String,
        expected: ProviderFailure,
    ) {
        assertEquals(expected, classify(status, body))
    }

    @Test
    fun `timeout_error wins at any status`() {
        listOf(200, 400, 429, 500, 529).forEach { status ->
            assertEquals(ProviderFailure.UpstreamTimeout, classify(status, body("timeout_error")))
        }
    }

    @Test
    fun `429 and overload statuses carry the wait`() {
        val headers = HttpHeaders().apply { set("retry-after", "2") }

        assertEquals(
            ProviderFailure.UpstreamRateLimited(2.seconds),
            classify(429, body("rate_limit_error"), headers),
        )
        assertEquals(
            ProviderFailure.ProviderUnavailable(2.seconds),
            classify(529, body("overloaded_error"), headers),
        )
    }

    @Test
    fun `a non-JSON body has no fields, and its status still decides`() {
        assertEquals(ProviderErrorFields.NONE, classifier.errorFields("<html>".encodeToByteArray()))
        assertEquals(ProviderFailure.UpstreamError, classify(404, "<html>"))
        assertEquals(ProviderFailure.UpstreamAuthFailed, classify(401, "<html>"))
    }

    @Test
    fun `error_code fills code, and an unsafe one is dropped`() {
        val safe = classifier.errorFields(body("rate_limit_error", "enforced_spend_limit_reached").encodeToByteArray())
        val unsafe = classifier.errorFields(body("rate_limit_error", "Not A Code").encodeToByteArray())

        assertEquals("enforced_spend_limit_reached", safe.code)
        assertEquals(null, unsafe.code)
        assertEquals("rate_limit_error", unsafe.type)
    }

    @Test
    fun `an unsafe error_code is no quota row`() {
        val failure = classify(429, body("rate_limit_error", "Enforced Spend"))

        assertEquals(ProviderFailure.UpstreamRateLimited(null), failure)
    }

    @ParameterizedTest
    @MethodSource("inBand")
    fun `the in-band table holds`(
        fields: ProviderErrorFields,
        expected: ProviderFailure,
    ) {
        assertEquals(expected, classifier.classifyInBand(fields))
    }

    companion object {
        private val NOW = Instant.parse("2026-11-04T00:00:00Z")

        private fun error(
            type: String,
            code: String? = null,
        ): String {
            val details = code?.let { ""","details":{"error_code":"$it"}""" }.orEmpty()
            return """{"type":"error","error":{"type":"$type","message":"m"$details}}"""
        }

        @JvmStatic
        fun statuses(): List<Arguments> =
            listOf(
                Arguments.of(400, error("invalid_request_error"), ProviderFailure.RejectedByProvider(400, null, null)),
                Arguments.of(401, error("authentication_error"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of(403, error("permission_error"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of(402, error("billing_error"), ProviderFailure.UpstreamQuotaExhausted),
                Arguments.of(404, error("not_found_error"), ProviderFailure.ModelNotFound),
                Arguments.of(404, "{}", ProviderFailure.UpstreamError),
                Arguments.of(408, "{}", ProviderFailure.UpstreamTimeout),
                Arguments.of(504, "{}", ProviderFailure.UpstreamTimeout),
                Arguments.of(413, error("request_too_large"), ProviderFailure.RejectedByProvider(413, null, null)),
                Arguments.of(422, "{}", ProviderFailure.RejectedByProvider(422, null, null)),
                Arguments.of(
                    429,
                    error("rate_limit_error", "enforced_spend_limit_reached"),
                    ProviderFailure.UpstreamQuotaExhausted,
                ),
                Arguments.of(429, error("rate_limit_error"), ProviderFailure.UpstreamRateLimited(null)),
                Arguments.of(503, "{}", ProviderFailure.ProviderUnavailable(null)),
                Arguments.of(529, error("overloaded_error"), ProviderFailure.ProviderUnavailable(null)),
                Arguments.of(409, error("conflict_error"), ProviderFailure.UpstreamError),
                Arguments.of(500, error("api_error"), ProviderFailure.UpstreamError),
                Arguments.of(418, "{}", ProviderFailure.UpstreamError),
            )

        private fun type(
            type: String?,
            code: String? = null,
        ) = named("$type/$code", ProviderErrorFields(code, type, null))

        @JvmStatic
        fun inBand(): List<Arguments> =
            listOf(
                Arguments.of(type("overloaded_error"), ProviderFailure.ProviderUnavailable(null)),
                Arguments.of(
                    type("rate_limit_error", "enforced_spend_limit_reached"),
                    ProviderFailure.UpstreamQuotaExhausted,
                ),
                Arguments.of(type("rate_limit_error"), ProviderFailure.UpstreamRateLimited(null)),
                Arguments.of(type("billing_error"), ProviderFailure.UpstreamQuotaExhausted),
                Arguments.of(type("authentication_error"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of(type("permission_error"), ProviderFailure.UpstreamAuthFailed),
                Arguments.of(type("invalid_request_error"), ProviderFailure.RejectedByProvider(400, null, null)),
                Arguments.of(type("request_too_large"), ProviderFailure.RejectedByProvider(413, null, null)),
                Arguments.of(type("not_found_error"), ProviderFailure.ModelNotFound),
                Arguments.of(type("timeout_error"), ProviderFailure.UpstreamTimeout),
                Arguments.of(type("api_error"), ProviderFailure.UpstreamError),
                Arguments.of(type("conflict_error"), ProviderFailure.UpstreamError),
                Arguments.of(type("something_new"), ProviderFailure.UpstreamError),
                Arguments.of(type(null), ProviderFailure.UpstreamError),
            )
    }
}
