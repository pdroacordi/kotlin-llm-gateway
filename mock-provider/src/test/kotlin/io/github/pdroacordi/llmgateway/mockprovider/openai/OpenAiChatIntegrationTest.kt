package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.KEY
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messages
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.messagesBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class OpenAiChatIntegrationTest(
    @Autowired private val client: WebTestClient,
) {
    @BeforeEach
    fun resetMock() = client.reset()

    @Test
    fun `a completion has the OpenAI shape and a provider request id`() {
        val result = client.chat(chatBody("Hello there"))

        assertEquals(200, result.status.value())
        assertEquals(MediaType.APPLICATION_JSON, result.responseHeaders.contentType)
        assertTrue(Regex("^req_[0-9a-f]{32}$").matches(result.responseHeaders.getFirst("x-request-id").orEmpty()))
        assertEquals("chat.completion", result.json<String>("$.object"))
        assertEquals("mock-chat", result.json<String>("$.model"))
        assertTrue(Regex("^chatcmpl-mock-\\d{12}$").matches(result.json<String>("$.id")))
        assertEquals("assistant", result.json<String>("$.choices[0].message.role"))
        assertEquals("stop", result.json<String>("$.choices[0].finish_reason"))
        assertEquals(16, result.json<Int>("$.usage.completion_tokens"))
    }

    @Test
    fun `the same request gives the same content and usage, and a different id`() {
        val first = client.chat(chatBody("Tell me about rivers"))
        val second = client.chat(chatBody("Tell me about rivers"))

        assertEquals(
            first.json<String>("$.choices[0].message.content"),
            second.json<String>("$.choices[0].message.content"),
        )
        assertEquals(first.json<Map<String, Any>>("$.usage"), second.json<Map<String, Any>>("$.usage"))
        assertNotEquals(first.json<String>("$.id"), second.json<String>("$.id"))
    }

    @Test
    fun `one changed character or another model changes the content`() {
        val base = client.chat(chatBody("Tell me about rivers")).json<String>("$.choices[0].message.content")
        val oneChar = client.chat(chatBody("Tell me about rivers!")).json<String>("$.choices[0].message.content")
        val model =
            client
                .chat(
                    chatBody("Tell me about rivers", model = "mock-chat-mini"),
                ).json<String>("$.choices[0].message.content")

        assertNotEquals(base, oneChar)
        assertNotEquals(base, model)
    }

    @Test
    fun `a cap cuts the content and finishes with length`() {
        val result =
            client.chat(
                chatBody("Tell me about rivers", extra = ""","max_completion_tokens":3,"max_tokens":9"""),
            )

        assertEquals("length", result.json<String>("$.choices[0].finish_reason"))
        assertEquals(3, result.json<Int>("$.usage.completion_tokens"))
    }

    @Test
    fun `usage invariants - total is prompt plus completion, prompt matches the Anthropic input split`() {
        client.putScenario("mock-chat", """{"otherwise":{"usage":{"cachedTokens":3,"cacheCreationTokens":2}}}""")
        client.putScenario("mock-claude", """{"otherwise":{"usage":{"cachedTokens":3,"cacheCreationTokens":2}}}""")
        val openAi = client.chat(chatBody("Count these tokens, please."))
        val anthropic = client.messages(messagesBody("Count these tokens, please."))

        val prompt = openAi.json<Int>("$.usage.prompt_tokens")
        assertEquals(prompt + openAi.json<Int>("$.usage.completion_tokens"), openAi.json<Int>("$.usage.total_tokens"))
        assertEquals(3, openAi.json<Int>("$.usage.prompt_tokens_details.cached_tokens"))
        assertEquals(
            prompt,
            anthropic.json<Int>("$.usage.input_tokens") + anthropic.json<Int>("$.usage.cache_read_input_tokens") +
                anthropic.json<Int>("$.usage.cache_creation_input_tokens"),
        )
        assertEquals(2, anthropic.json<Int>("$.usage.cache_creation_input_tokens"))
    }

    @Test
    fun `usage omit removes the usage member`() {
        client.putScenario("mock-chat", """{"otherwise":{"usage":{"omit":true}}}""")

        assertFalse("\"usage\"" in client.chat(chatBody("x")).bodyText())
    }

    @Test
    fun `usage overrides change only the report`() {
        client.putScenario("mock-chat", """{"otherwise":{"usage":{"promptTokens":1000,"completionTokens":7}}}""")
        val result = client.chat(chatBody("x"))

        assertEquals(1000, result.json<Int>("$.usage.prompt_tokens"))
        assertEquals(7, result.json<Int>("$.usage.completion_tokens"))
        assertEquals(1007, result.json<Int>("$.usage.total_tokens"))
    }

    @Test
    fun `n other than 1 is 400 unsupported_value`() {
        client
            .post()
            .uri("/v1/chat/completions")
            .header("authorization", "Bearer $KEY")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(chatBody("x", extra = ""","n":2"""))
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .json(
                """{"error":{"message":"mock-provider supports only n = 1.","type":"invalid_request_error",
                "param":"n","code":"unsupported_value"}}""",
                JsonCompareMode.STRICT,
            )
    }

    @Test
    fun `no credential is 401 invalid_api_key, any bearer is accepted, x-api-key is not enough`() {
        val missing = client.chat(chatBody("x"), headers = emptyMap())
        val apiKeyOnly = client.chat(chatBody("x"), headers = mapOf("x-api-key" to KEY))
        val anyBearer = client.chat(chatBody("x"), headers = mapOf("authorization" to "Bearer anything"))

        assertEquals(401, missing.status.value())
        assertEquals(
            """{"error":{"message":"No API key was provided to mock-provider.","type":"invalid_request_error",""" +
                """"param":null,"code":"invalid_api_key"}}""",
            missing.bodyText(),
        )
        assertEquals(401, apiKeyOnly.status.value())
        assertEquals(200, anyBearer.status.value())
    }

    @Test
    fun `credentials are checked before the body - no key and bad JSON is 401`() {
        val result = client.chat("""{"model":""", headers = emptyMap())

        assertEquals(401, result.status.value())
        assertEquals("invalid_api_key", result.json<String>("$.error.code"))
    }

    @Test
    fun `echo and fixed content modes`() {
        client.putScenario(
            "mock-chat",
            """{"steps":[{"content":{"mode":"echo"}},{"content":{"mode":"fixed","text":"Fixed!"}}]}""",
        )

        assertEquals(
            "say this back",
            client.chat(chatBody("say this back")).json<String>("$.choices[0].message.content"),
        )
        assertEquals("Fixed!", client.chat(chatBody("x")).json<String>("$.choices[0].message.content"))
    }
}
