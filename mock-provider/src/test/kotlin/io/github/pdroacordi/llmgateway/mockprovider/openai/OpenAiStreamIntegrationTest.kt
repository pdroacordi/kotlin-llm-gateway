package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.FixedClockConfiguration
import io.github.pdroacordi.llmgateway.mockprovider.Golden
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.bodyText
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chat
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.chatBody
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.json
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.putScenario
import io.github.pdroacordi.llmgateway.mockprovider.MockHttp.reset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.context.annotation.Import
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(FixedClockConfiguration::class)
class OpenAiStreamIntegrationTest {
    // Field injection: @DirtiesContext(BEFORE_METHOD) re-injects fields, never constructor parameters.
    @Autowired
    @Suppress("VarCouldBeVal") // Spring sets it.
    private lateinit var client: WebTestClient

    @BeforeEach
    fun fixedContent() {
        client.reset()
        client.putScenario("mock-chat", Golden.FIXED_SCENARIO)
    }

    // A fresh context per golden test, so the arrival sequence (and the id) is 1.
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `the stream with include_usage equals the golden file byte for byte`() {
        val result = client.chat(Golden.chatRequest(stream = true, includeUsage = true))

        assertEquals(200, result.status.value())
        assertEquals("text/event-stream;charset=UTF-8", result.responseHeaders.getFirst("content-type"))
        assertEquals("no-cache", result.responseHeaders.cacheControl)
        assertEquals(Golden.read("openai-chat-stream-include-usage.sse"), result.bodyText())
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `the stream without include_usage equals the golden file byte for byte`() {
        assertEquals(Golden.read("openai-chat-stream.sse"), client.chat(Golden.chatRequest(stream = true)).bodyText())
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD)
    fun `the non-stream completion equals the golden file`() {
        assertEquals(Golden.read("openai-chat.json"), client.chat(Golden.chatRequest(stream = false)).bodyText())
    }

    @Test
    fun `stream and non-stream agree on text and usage`() {
        client.putScenario("mock-chat", """{"otherwise":{"content":{"mode":"lorem","completionTokens":9}}}""")
        val body = chatBody("Agree on this", extra = ""","stream_options":{"include_usage":true}""")
        val plain = client.chat(body)
        val stream = client.chat(body.replaceFirst("{", """{"stream":true,"""))
        val chunks =
            stream
                .bodyText()
                .split("\n\n")
                .filter { it.startsWith("data: {") }
                .map { MAPPER.readTree(it.removePrefix("data: ")) }
        val text = chunks.mapNotNull { it.at("/choices/0/delta/content").stringValue(null) }.joinToString("")
        val usage = chunks.last().get("usage")

        assertEquals(plain.json<String>("$.choices[0].message.content"), text)
        assertEquals(plain.json<Int>("$.usage.prompt_tokens"), usage.get("prompt_tokens").intValue())
        assertEquals(plain.json<Int>("$.usage.completion_tokens"), usage.get("completion_tokens").intValue())
        // One chunk per piece: the role chunk, 9 content chunks, the finish chunk, the usage chunk.
        assertEquals(12, chunks.size)
        assertTrue(stream.bodyText().endsWith("data: [DONE]\n\n"))
    }

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
