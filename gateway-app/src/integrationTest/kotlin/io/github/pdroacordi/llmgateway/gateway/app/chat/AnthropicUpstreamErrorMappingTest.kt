package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult

private fun AnthropicBackedTest.errorCode(result: EntityExchangeResult<String>): String =
    json
        .readTree(result.responseBody)
        .at("/error/code")
        .stringValue()

/** GW-03 section 4, by status, against the mock. Each row is the injected error, the status and the code seen. */
class AnthropicUpstreamErrorMappingTest : AnthropicBackedTest() {
    class Row(
        val error: String,
        val status: Int,
        val code: String,
    ) {
        override fun toString(): String = error
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `each status gives its row, with one journal entry`(row: Row) {
        claudeScenario("""{"otherwise":{"error":${row.error}}}""")

        val result = chat(CLAUDE_MINIMAL).result()

        assertEquals(row.status, result.status.value()) { result.responseBody }
        assertEquals(row.code, errorCode(result))
        assertEquals(1, journal().size)
    }

    @Test
    fun `retry-after reaches the client, and the provider's request-id does not`() {
        claudeScenario("""{"otherwise":{"error":{"status":429,"retryAfterSeconds":7,"headers":{"x-other":"1"}}}}""")

        val headers = chat(CLAUDE_MINIMAL).result().responseHeaders

        assertEquals("7", headers.getFirst("retry-after"))
        assertNull(headers.getFirst("x-other"))
        assertFalse(checkNotNull(headers.getFirst("x-request-id")).startsWith("req_"))
    }

    @Test
    fun `quota and auth rows say not to retry`() {
        claudeScenario("""{"otherwise":{"error":{"status":402}}}""")

        assertEquals("false", chat(CLAUDE_MINIMAL).result().responseHeaders.getFirst("x-should-retry"))
    }

    companion object {
        @JvmStatic
        fun rows(): List<Row> =
            listOf(
                Row("""{"status":500,"type":"timeout_error"}""", 504, "upstream_timeout"),
                Row("""{"status":400}""", 400, "upstream_rejected_request"),
                Row("""{"status":401}""", 502, "upstream_auth_failed"),
                Row("""{"status":403}""", 502, "upstream_auth_failed"),
                Row("""{"status":402}""", 503, "upstream_quota_exhausted"),
                Row("""{"status":404}""", 404, "model_not_found"),
                Row("""{"status":404,"type":"other_error"}""", 502, "upstream_error"),
                Row("""{"status":408}""", 504, "upstream_timeout"),
                Row("""{"status":504}""", 504, "upstream_timeout"),
                Row("""{"status":413}""", 413, "upstream_rejected_request"),
                Row("""{"status":422}""", 422, "upstream_rejected_request"),
                Row(
                    """{"status":429,"code":"enforced_spend_limit_reached"}""",
                    503,
                    "upstream_quota_exhausted",
                ),
                Row("""{"status":429}""", 429, "upstream_rate_limited"),
                Row("""{"status":503}""", 503, "provider_unavailable"),
                Row("""{"status":529}""", 503, "provider_unavailable"),
                Row("""{"status":409}""", 502, "upstream_error"),
                Row("""{"status":500}""", 502, "upstream_error"),
                Row("""{"status":418}""", 502, "upstream_error"),
            )
    }
}

/** The same rows on a stream, and the in-band table before commit: a status, with no stream header. */
class AnthropicStreamUpstreamErrorMappingTest : AnthropicBackedTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.pdroacordi.llmgateway.gateway.app.chat.AnthropicUpstreamErrorMappingTest#rows")
    fun `each status before the first frame gives its row`(row: AnthropicUpstreamErrorMappingTest.Row) {
        claudeScenario("""{"otherwise":{"error":${row.error}}}""")

        val result = chat(CLAUDE_STREAM).result()

        assertEquals(row.status, result.status.value()) { result.responseBody }
        assertEquals(row.code, errorCode(result))
        assertNull(result.responseHeaders.getFirst("x-gateway-deployment"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"status":529} | 503 | provider_unavailable""",
            """{"status":429} | 429 | upstream_rate_limited""",
            """{"status":429,"code":"enforced_spend_limit_reached"} | 503 | upstream_quota_exhausted""",
            """{"status":402} | 503 | upstream_quota_exhausted""",
            """{"status":401} | 502 | upstream_auth_failed""",
            """{"status":403} | 502 | upstream_auth_failed""",
            """{"status":400} | 400 | upstream_rejected_request""",
            """{"status":413} | 413 | upstream_rejected_request""",
            """{"status":404} | 404 | model_not_found""",
            """{"status":504} | 504 | upstream_timeout""",
            """{"status":500} | 502 | upstream_error""",
            """{"status":409} | 502 | upstream_error""",
        ],
    )
    fun `an in-band error straight after message_start, ping and a block start is a real status`(
        error: String,
        status: Int,
        code: String,
    ) {
        claudeScenario("""{"otherwise":{"error":$error,"fault":{"kind":"stream_error","afterPieces":0}}}""")

        val result = chat(CLAUDE_STREAM).result()

        assertEquals(status, result.status.value()) { result.responseBody }
        assertEquals(code, errorCode(result))
        assertNull(result.responseHeaders.getFirst("x-gateway-deployment"))
    }

    @Test
    fun `a disconnect before any content is 502, not a frame`() {
        claudeScenario("""{"otherwise":{"fault":{"kind":"disconnect","afterPieces":0}}}""")

        assertEquals(502, chat(CLAUDE_STREAM).result().status.value())
    }
}

@TestPropertySource(properties = ["llmgw.providers.anthropic.stream.first-byte-timeout=500ms"])
class AnthropicStreamFirstByteDeadlineTest : AnthropicBackedTest() {
    @Test
    fun `hang at first-byte-timeout gives 504, and the mock sees the call cancelled within 2 s`() {
        claudeScenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val result = chat(CLAUDE_STREAM).result()

        assertEquals(504, result.status.value())
        assertEquals("upstream_timeout", errorCode(result))
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        eventually { journalOf(requestId).singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }
}
