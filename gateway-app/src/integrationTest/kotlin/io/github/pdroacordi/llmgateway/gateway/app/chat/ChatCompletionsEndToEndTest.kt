package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.test.web.reactive.server.EntityExchangeResult
import kotlin.uuid.Uuid

class ChatCompletionsEndToEndTest(
    @Autowired private val environment: Environment,
) : MockBackedTest() {
    private fun answered(
        body: String = MINIMAL,
        headers: Map<String, String> = emptyMap(),
    ): EntityExchangeResult<String> =
        chat(body, headers)
            .expectStatus()
            .isOk
            .expectBody(String::class.java)
            .returnResult()

    private fun EntityExchangeResult<String>.requestId(): String = checkNotNull(responseHeaders.getFirst(REQUEST_ID))

    @Test
    fun `a completion gives 200 with a UUIDv7 and the deployment`() {
        val result = answered()

        assertEquals("application/json", result.responseHeaders.contentType.toString())
        assertEquals('7', Uuid.parse(result.requestId()).toString()[14])
        assertEquals("openai", result.responseHeaders.getFirst("x-gateway-deployment"))
    }

    @Test
    fun `the journal ties the provider call to the gateway's request ID, once`() {
        val requestId = answered().requestId()

        val entry = journalOf(requestId).single()
        assertEquals(listOf(requestId), entry.at("/headers/x-client-request-id").values().map { it.stringValue() })
    }

    @Test
    fun `the provider sees the configured key, never the client's credential or account headers`() {
        val requestId =
            answered(
                headers =
                    mapOf(
                        HttpHeaders.AUTHORIZATION to TestKeys.BEARER,
                        "OpenAI-Organization" to "org-client",
                        "OpenAI-Project" to "proj-client",
                    ),
            ).requestId()

        val entry = journalOf(requestId).single()
        assertEquals(fingerprint(KEY), entry.at("/credential/fingerprint").stringValue())
        assertTrue(entry.at("/headers/openai-organization").isMissingNode) { entry.toString() }
        assertTrue(entry.at("/headers/openai-project").isMissingNode) { entry.toString() }
    }

    @Test
    fun `an unknown member, a big integer and a float reach the provider unchanged`() {
        val requestId =
            answered(
                """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],""" +
                    """"x_future":{"a":[1,null]},"seed":9007199254740993,"temperature":0.7}""",
            ).requestId()

        val body = journalOf(requestId).single().at("/body").stringValue()
        assertTrue(""""x_future":{"a":[1,null]}""" in body) { body }
        assertTrue(""""seed":9007199254740993""" in body) { body }
        assertTrue(""""temperature":0.7""" in body) { body }
    }

    @Test
    fun `absent content goes upstream as a null member and a number keeps its value, not its spelling`() {
        val requestId =
            answered(
                """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"},""" +
                    """{"role":"tool","tool_call_id":"c1"}],"x_big":1e5,"x_zero":-0.0}""",
            ).requestId()

        val body = journalOf(requestId).single().at("/body").stringValue()
        assertTrue("""{"role":"tool","content":null,"tool_call_id":"c1"}""" in body) { body }
        assertTrue(""""x_big":1E+5""" in body) { body }
        assertTrue(""""x_zero":0.0""" in body) { body }
    }

    @Test
    fun `the client's x-client-request-id is echoed and not forwarded`() {
        val result = answered(headers = mapOf("x-client-request-id" to "client-correlation-1"))

        assertEquals("client-correlation-1", result.responseHeaders.getFirst("x-client-request-id"))
        val entry = journalOf(result.requestId()).single()
        assertFalse("client-correlation-1" in entry.toString()) { entry.toString() }
    }

    @Test
    fun `with provider usage there is no estimate header and usage equals the mock's`() {
        scenario("""{"otherwise":{"usage":{"promptTokens":11,"completionTokens":5,"cachedTokens":3}}}""")

        val result = answered()

        assertNull(result.responseHeaders.getFirst(ESTIMATED))
        val usage = mapper.readTree(result.responseBody).get("usage")
        assertEquals(11, usage.get("prompt_tokens").intValue())
        assertEquals(5, usage.get("completion_tokens").intValue())
        assertEquals(16, usage.get("total_tokens").intValue())
        assertEquals(3, usage.at("/prompt_tokens_details/cached_tokens").intValue())
    }

    @Test
    fun `omitted usage gives 200 with an estimated usage and the header`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val result = answered()

        assertEquals("true", result.responseHeaders.getFirst(ESTIMATED))
        val usage = mapper.readTree(result.responseBody).get("usage")
        assertTrue(usage.get("prompt_tokens").intValue() > 0) { usage.toString() }
        assertEquals(
            usage.get("prompt_tokens").intValue() + usage.get("completion_tokens").intValue(),
            usage.get("total_tokens").intValue(),
        )
    }

    @Test
    fun `the default bind address is loopback`() {
        assertEquals("127.0.0.1", environment.getProperty("server.address"))
    }

    private companion object {
        const val REQUEST_ID = "x-request-id"
        const val ESTIMATED = "x-gateway-usage-estimated"
    }
}
