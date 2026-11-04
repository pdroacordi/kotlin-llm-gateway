package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.FixedClockConfiguration
import io.github.pdroacordi.llmgateway.mockprovider.Golden
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messagesBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(FixedClockConfiguration::class)
class AnthropicMessagesIntegrationTest {
    // Field injection: @DirtiesContext(BEFORE_METHOD) re-injects fields, never constructor parameters.
    @Autowired
    @Suppress("VarCouldBeVal") // Spring sets it.
    private lateinit var client: WebTestClient

    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `the stream equals the golden file byte for byte`() {
        client.putScenario("mock-claude", Golden.FIXED_SCENARIO)
        val result = client.messages(Golden.messagesRequest(stream = true))

        assertEquals(200, result.status.value())
        assertEquals("text/event-stream;charset=UTF-8", result.responseHeaders.getFirst("content-type"))
        assertEquals("req_00000000000000000000000000000001", result.responseHeaders.getFirst("request-id"))
        assertEquals(Golden.read("anthropic-messages-stream.sse"), result.bodyText())
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `the non-stream message equals the golden file`() {
        client.putScenario("mock-claude", Golden.FIXED_SCENARIO)

        assertEquals(
            Golden.read("anthropic-messages.json"),
            client.messages(Golden.messagesRequest(stream = false)).bodyText(),
        )
    }

    @Test
    fun `deterministic content, different ids, and the model changes the content`() {
        val first = client.messages(messagesBody("Describe a forest"))
        val second = client.messages(messagesBody("Describe a forest"))
        val other = client.messages(messagesBody("Describe a forest", model = "mock-claude-2"))

        assertEquals(first.json<String>("$.content[0].text"), second.json<String>("$.content[0].text"))
        assertEquals(first.json<Map<String, Any>>("$.usage"), second.json<Map<String, Any>>("$.usage"))
        assertNotEquals(first.json<String>("$.id"), second.json<String>("$.id"))
        assertNotEquals(first.json<String>("$.content[0].text"), other.json<String>("$.content[0].text"))
        assertEquals("end_turn", first.json<String>("$.stop_reason"))
    }

    @Test
    fun `stream and non-stream agree on text and output tokens`() {
        val plain = client.messages(messagesBody("Agree on this"))
        val stream = client.messages(messagesBody("Agree on this", extra = ""","stream":true"""))
        val deltas =
            Regex(""""text_delta","text":"([^"]*)"""").findAll(stream.bodyText()).joinToString("") { it.groupValues[1] }

        assertEquals(plain.json<String>("$.content[0].text"), deltas)
        assertTrue(""""usage":{"output_tokens":${plain.json<Int>("$.usage.output_tokens")}}""" in stream.bodyText())
    }

    @Test
    fun `a bearer token is accepted too, no credential is 401 authentication_error`() {
        val bearer = client.messages(messagesBody("x"), headers = mapOf("authorization" to "Bearer $KEY"))
        val missing = client.messages(messagesBody("x"), headers = emptyMap())

        assertEquals(200, bearer.status.value())
        assertEquals(401, missing.status.value())
        assertEquals("error", missing.json<String>("$.type"))
        assertEquals("authentication_error", missing.json<String>("$.error.type"))
        assertEquals("No API key was provided to mock-provider.", missing.json<String>("$.error.message"))
        assertEquals(missing.responseHeaders.getFirst("request-id"), missing.json<String>("$.request_id"))
    }

    @Test
    fun `credentials are checked before the body - no key and bad JSON is 401`() {
        val result = client.messages("""{"model":""", headers = emptyMap())

        assertEquals(401, result.status.value())
        assertEquals("authentication_error", result.json<String>("$.error.type"))
    }

    @Test
    fun `max_tokens missing is 400 invalid_request_error`() {
        val result = client.messages("""{"model":"mock-claude","messages":[{"role":"user","content":"x"}]}""")

        assertEquals(400, result.status.value())
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
        assertEquals(result.responseHeaders.getFirst("request-id"), result.json<String>("$.request_id"))
    }

    @Test
    fun `max_tokens 0 gives empty content and max_tokens`() {
        val result =
            client.messages(
                """{"model":"mock-claude","max_tokens":0,"messages":[{"role":"user","content":"x"}]}""",
            )

        assertEquals(emptyList<Any>(), result.json<List<Any>>("$.content"))
        assertEquals("max_tokens", result.json<String>("$.stop_reason"))
        assertEquals(0, result.json<Int>("$.usage.output_tokens"))
    }

    @Test
    fun `cache fields follow a usage scenario`() {
        client.putScenario("mock-claude", """{"otherwise":{"usage":{"cachedTokens":4,"cacheCreationTokens":3}}}""")
        val result = client.messages(messagesBody("Use the cache please"))

        assertEquals(4, result.json<Int>("$.usage.cache_read_input_tokens"))
        assertEquals(3, result.json<Int>("$.usage.cache_creation_input_tokens"))
        assertEquals(3, result.json<Int>("$.usage.cache_creation.ephemeral_5m_input_tokens"))
    }

    @Test
    fun `usage omit removes usage from the body and from both stream events`() {
        client.putScenario("mock-claude", """{"otherwise":{"usage":{"omit":true}}}""")

        assertTrue("usage" !in client.messages(messagesBody("x")).bodyText())
        assertTrue("usage" !in client.messages(messagesBody("x", extra = ""","stream":true""")).bodyText())
    }

    @Test
    fun `a fault at zero pieces after a thinking prefix comes before any content`() {
        val scenario =
            """{"otherwise":{"content":{"mode":"fixed","text":"","thinking":[{"signature":"s"}],
            "toolCalls":[{"name":"get","input":{"a":1},"chunkChars":3}]},
            "fault":{"kind":"stream_error","afterPieces":0}}}"""
        client.putScenario("mock-claude", scenario)

        val body = client.messages(messagesBody("Hi", extra = ""","stream":true""")).bodyText()

        assertTrue("signature_delta" in body) { body }
        assertTrue("event: error" in body) { body }
        assertTrue("\"a\":1" !in body) { body }
    }

    @Test
    fun `a conversation that leaves a tool_use unanswered is a 400 in Anthropic's shape`() {
        val body =
            """{"model":"mock-claude","max_tokens":8,"messages":[
            {"role":"assistant","content":[{"type":"tool_use","id":"a","name":"n","input":{}}]},
            {"role":"user","content":"no result"}]}"""
        val result = client.messages(body)

        assertEquals(400, result.status.value())
        assertEquals("invalid_request_error", result.json<String>("$.error.type"))
    }
}
