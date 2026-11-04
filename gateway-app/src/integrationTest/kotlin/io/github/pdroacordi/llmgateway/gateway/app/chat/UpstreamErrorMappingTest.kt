package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

private const val SERVER = "server_error"
private const val INVALID = "invalid_request_error"
private const val REPORT = "Include the request ID <id> if you report it."
private const val REJECTED = "The provider rejected the request; see the code and param members."
private const val RATE_LIMITED = "The provider is rate limiting the gateway; retry later."
private val JSON: JsonMapper = JsonMapper.builder().build()
private val NO_RETRY = mapOf("x-should-retry" to "false")
private val WAIT = mapOf("retry-after" to "7", "retry-after-ms" to "7000")

/** One row of GW-01 section 4; `<id>` in [message] stands for the response's `x-request-id`. */
data class ExpectedReply(
    val status: Int,
    val type: String,
    val code: String,
    val message: String,
    val param: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

private val MODEL_NOT_FOUND =
    ExpectedReply(404, INVALID, "model_not_found", "The requested model does not exist or is not available.", "model")
private val AUTH_FAILED =
    ExpectedReply(
        502,
        SERVER,
        "upstream_auth_failed",
        "The gateway could not authenticate with the provider. $REPORT",
        headers = NO_RETRY,
    )
private val QUOTA_EXHAUSTED =
    ExpectedReply(
        503,
        SERVER,
        "upstream_quota_exhausted",
        "The gateway's provider account has no quota left. $REPORT",
        headers = NO_RETRY,
    )
val UNAVAILABLE =
    ExpectedReply(503, SERVER, "provider_unavailable", "The provider cannot be reached or is overloaded. $REPORT")
val TIMED_OUT = ExpectedReply(504, SERVER, "upstream_timeout", "The provider did not answer in time. $REPORT")
private val UNREADABLE =
    ExpectedReply(502, SERVER, "upstream_error", "The provider returned an error or an unreadable response. $REPORT")

private fun rejected(
    status: Int,
    code: String = "upstream_rejected_request",
    param: String? = null,
) = ExpectedReply(status, INVALID, code, REJECTED, param)

fun assertReply(
    result: EntityExchangeResult<String>,
    expected: ExpectedReply,
) {
    val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
    val error =
        linkedMapOf(
            "message" to expected.message.replace("<id>", requestId),
            "type" to expected.type,
            "param" to expected.param,
            "code" to expected.code,
        )
    assertEquals(expected.status, result.status.value())
    assertEquals(MediaType.APPLICATION_JSON, result.responseHeaders.contentType)
    assertEquals(JSON.valueToTree<JsonNode>(mapOf("error" to error)), JSON.readTree(result.responseBody))
    expected.headers.forEach { (name, value) -> assertEquals(value, result.responseHeaders.getFirst(name), name) }
    if ("retry-after" !in expected.headers) assertNull(result.responseHeaders.getFirst("retry-after"))
    if ("x-should-retry" !in expected.headers) assertNull(result.responseHeaders.getFirst("x-should-retry"))
}

fun WebTestClient.ResponseSpec.result(): EntityExchangeResult<String> = expectBody(String::class.java).returnResult()

class UpstreamErrorMappingTest : MockBackedTest() {
    class Row(
        private val name: String,
        val error: String,
        val expected: ExpectedReply,
    ) {
        override fun toString(): String = name
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientRows", "serverRows")
    fun `each injected condition gives its row, with one journal entry`(row: Row) {
        scenario("""{"otherwise":{"error":${row.error}}}""")

        assertReply(chat().result(), row.expected)
        assertEquals(1, journal().size)
    }

    @Test
    fun `retry-after and retry-after-ms reach the client, and no other provider header does`() {
        scenario(
            """{"otherwise":{"error":{"status":429,"retryAfterSeconds":7,"retryAfterMs":7000,"headers":{
            "x-ratelimit-limit-requests":"100","openai-organization":"org-canary","openai-processing-ms":"5"}}}}""",
        )

        val headers = chat().result().responseHeaders

        assertEquals("7", headers.getFirst("retry-after"))
        assertEquals("7000", headers.getFirst("retry-after-ms"))
        assertFalse(headers.headerNames().any { it.startsWith("x-ratelimit") }) { headers.toString() }
        assertFalse(headers.headerNames().any { it.startsWith("openai-") }) { headers.toString() }
    }

    @Test
    fun `the provider's x-request-id is never relayed`() {
        val ids =
            chat()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseHeaders["x-request-id"]
                .orEmpty()

        assertEquals(1, ids.size)
        assertFalse(ids.single().startsWith("req_")) { ids.toString() }
    }

    @Test
    fun `a malformed 200 gives 502 upstream_error`() {
        scenario("""{"otherwise":{"fault":{"kind":"malformed"}}}""")

        assertReply(chat().result(), UNREADABLE)
    }

    @Test
    fun `a connection closed mid-response gives 502 upstream_error`() {
        scenario("""{"otherwise":{"fault":{"kind":"disconnect"}}}""")

        assertReply(chat().result(), UNREADABLE)
    }

    companion object {
        @JvmStatic
        fun clientRows(): List<Row> =
            listOf(
                Row("404 model", """{"status":404}""", MODEL_NOT_FOUND),
                Row(
                    "400 with code and param",
                    """{"status":400,"code":"context_length_exceeded","param":"messages[0].content"}""",
                    rejected(400, "context_length_exceeded", "messages[0].content"),
                ),
                Row("400 bare", """{"status":400}""", rejected(400)),
                Row("413", """{"status":413}""", rejected(413)),
                Row("422", """{"status":422}""", rejected(422)),
                Row("400 free text", """{"status":400,"code":"Has Spaces","param":"a b!"}""", rejected(400)),
                Row(
                    "429 with a wait",
                    """{"status":429,"retryAfterSeconds":7}""",
                    ExpectedReply(429, "rate_limit_error", "upstream_rate_limited", RATE_LIMITED, headers = WAIT),
                ),
                Row(
                    "429 with no wait",
                    """{"status":429}""",
                    ExpectedReply(429, "rate_limit_error", "upstream_rate_limited", RATE_LIMITED),
                ),
            )

        @JvmStatic
        fun serverRows(): List<Row> =
            listOf(
                Row("401", """{"status":401}""", AUTH_FAILED),
                Row("403", """{"status":403}""", AUTH_FAILED),
                Row("429 quota by code", """{"status":429,"code":"insufficient_quota"}""", QUOTA_EXHAUSTED),
                Row("429 quota by type", """{"status":429,"type":"insufficient_quota","code":"x"}""", QUOTA_EXHAUSTED),
                Row("503", """{"status":503}""", UNAVAILABLE),
                Row("503 with a wait", """{"status":503,"retryAfterMs":7000}""", UNAVAILABLE.copy(headers = WAIT)),
                Row("408", """{"status":408}""", TIMED_OUT),
                Row("504", """{"status":504}""", TIMED_OUT),
                Row("404 not the model", """{"status":404,"code":"not_found"}""", UNREADABLE),
                Row("500", """{"status":500}""", UNREADABLE),
                Row("502", """{"status":502}""", UNREADABLE),
                Row("409", """{"status":409}""", UNREADABLE),
            )
    }
}

@TestPropertySource(properties = ["llmgw.providers.openai.request-timeout=500ms"])
class UpstreamDeadlineTest : MockBackedTest() {
    @Test
    fun `a hang past request-timeout gives 504 and the mock sees the call cancelled within 2 s`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")

        val result = chat().result()

        assertReply(result, TIMED_OUT)
        val requestId = checkNotNull(result.responseHeaders.getFirst("x-request-id"))
        eventually { journalOf(requestId).singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }
}

@TestPropertySource(properties = ["llmgw.providers.openai.max-response-size=1KB"])
class UpstreamResponseLimitTest : MockBackedTest() {
    @Test
    fun `a response over max-response-size gives 502, not 500 and not 413`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"${"word ".repeat(600)}"}}}""")

        assertReply(chat().result(), UNREADABLE)
    }
}

// Not a MockBackedTest, whose dynamic base URL would win: this suite's application.properties gives a closed port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClosedProviderPortTest(
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `a closed port gives 503 provider_unavailable`() {
        val result =
            WebTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$port")
                .build()
                .post()
                .uri(MockBackedTest.ROUTE)
                .header("Authorization", TestKeys.BEARER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(MockBackedTest.MINIMAL)
                .exchange()
                .result()

        assertReply(result, UNAVAILABLE)
    }
}
