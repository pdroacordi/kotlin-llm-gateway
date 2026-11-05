package io.github.pdroacordi.llmgateway.gateway.app.chat

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.errors.RateLimitException
import com.openai.errors.SseException
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionStreamOptions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.jvm.optionals.getOrNull

/** The official SDK against the gateway and the mock; never a provider, so not a `live` test (ADR-GW-01-009). */
class OpenAiSdkStreamContractTest : MockBackedTest() {
    private val client: OpenAIClient by lazy {
        OpenAIOkHttpClient
            .builder()
            .baseUrl("http://127.0.0.1:$port/v1")
            .apiKey(TestKeys.TEAM_KEY)
            .maxRetries(0)
            .responseValidation(true)
            .timeout(Duration.ofSeconds(30))
            .build()
    }

    private val params: ChatCompletionCreateParams =
        ChatCompletionCreateParams
            .builder()
            .model(MODEL)
            .addUserMessage("Hi")
            .streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build())
            .build()

    private fun chunks(): List<ChatCompletionChunk> =
        client
            .chat()
            .completions()
            .createStreaming(params)
            .use { it.stream().toList() }

    @Test
    fun `createStreaming reads every chunk under responseValidation`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val chunks = chunks()

        chunks.forEach { it.validate() }
        assertTrue(chunks.size > 2) { chunks.size.toString() }
        assertEquals(MODEL, chunks.first().model())
    }

    @Test
    fun `ChatCompletionAccumulator rebuilds the mock's text`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")
        val accumulator = ChatCompletionAccumulator.create()

        chunks().forEach { accumulator.accumulate(it) }

        val message =
            accumulator
                .chatCompletion()
                .choices()
                .single()
                .message()
        assertEquals("Hi there", message.content().orElseThrow())
    }

    @Test
    fun `the usage chunk is read, with no extension member`() {
        val last = chunks().last()

        assertTrue(last.choices().isEmpty())
        val usage = last.usage().orElseThrow()
        assertTrue(usage.promptTokens() > 0)
        assertFalse(ChatExtensions.MEMBER in last._allProperties())
    }

    @Test
    fun `the extension member stays an additional property under responseValidation`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val last = chunks().last()

        last.validate()
        assertTrue(last.usage().isPresent)
        val extension = last._allProperties()[ChatExtensions.MEMBER]
        val member = extension?.asObject()?.getOrNull()?.get(ChatExtensions.USAGE_ESTIMATED_MEMBER)
        assertEquals(JsonValue.from(true), member)
    }

    @Test
    fun `stream_error raises SseException`() {
        scenario("""{"otherwise":{"error":{"status":500},"fault":{"kind":"stream_error","afterPieces":1}}}""")

        val failure = assertThrows<SseException> { chunks() }

        assertEquals("upstream_error", failure.code().orElseThrow())
    }

    @Test
    fun `a status before the first frame raises the SDK's exception for it`() {
        scenario("""{"otherwise":{"error":{"status":429}}}""")

        assertThrows<RateLimitException> { chunks() }
    }

    private companion object {
        const val MODEL = "mock-chat"
    }
}
