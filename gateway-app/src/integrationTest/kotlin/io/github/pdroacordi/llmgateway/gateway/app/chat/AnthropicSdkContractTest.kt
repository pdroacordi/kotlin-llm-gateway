package io.github.pdroacordi.llmgateway.gateway.app.chat

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.RateLimitException
import com.openai.errors.SseException
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionChunk
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/** The official SDK against the gateway and the mock's Messages route; never a provider, so not a `live` test. */
class AnthropicSdkContractTest : AnthropicBackedTest() {
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
            .model(CLAUDE)
            .addUserMessage("Hi")
            .build()

    private fun chunks(): List<ChatCompletionChunk> =
        client
            .chat()
            .completions()
            .createStreaming(params)
            .use { it.stream().toList() }

    private fun ChatCompletion.calls(): List<Pair<String, String>> =
        choices()
            .single()
            .message()
            .toolCalls()
            .orElseThrow()
            .map { call ->
                val function = call.asFunction().function()
                function.name() to function.arguments()
            }

    @Test
    fun `the SDK completes a chat and the response passes validate`() {
        val completion = client.chat().completions().create(params)

        completion.validate()
        assertEquals(CLAUDE, completion.model())
        assertTrue(completion.usage().isPresent)
    }

    @Test
    fun `tool calls pass validate, and the stream accumulator reads the same tool_calls`() {
        claudeScenario(TOOLS)
        val accumulator = ChatCompletionAccumulator.create()

        val completion = client.chat().completions().create(params)
        chunks().forEach {
            it.validate()
            accumulator.accumulate(it)
        }

        completion.validate()
        assertEquals(2, completion.calls().size)
        assertEquals(completion.calls(), accumulator.chatCompletion().calls())
        assertEquals(
            ChatCompletion.Choice.FinishReason.TOOL_CALLS,
            accumulator
                .chatCompletion()
                .choices()
                .single()
                .finishReason(),
        )
    }

    @Test
    fun `no separate role chunk is needed, and thinking in an extension member passes validate`() {
        claudeScenario(
            """{"otherwise":{"content":{"mode":"fixed","text":"Hi there","thinking":[{"signature":"sig-1"}]}}}""",
        )
        val accumulator = ChatCompletionAccumulator.create()

        chunks().forEach {
            it.validate()
            accumulator.accumulate(it)
        }

        val message =
            accumulator
                .chatCompletion()
                .choices()
                .single()
                .message()
        assertEquals("Hi there", message.content().orElseThrow())
    }

    @Test
    fun `an empty answer, whose first chunk is the finish chunk, reads`() {
        claudeScenario("""{"otherwise":{"content":{"mode":"fixed","text":""}}}""")

        assertEquals(1, chunks().size)
    }

    @Test
    fun `a refused member raises BadRequestException with its code`() {
        val penalised = params.toBuilder().presencePenalty(0.5).build()

        val failure = assertThrows<BadRequestException> { client.chat().completions().create(penalised) }

        assertEquals("unsupported_value", failure.code().orElseThrow())
    }

    @Test
    fun `a provider 429 raises RateLimitException`() {
        claudeScenario("""{"otherwise":{"error":{"status":429}}}""")

        assertThrows<RateLimitException> { client.chat().completions().create(params) }
    }

    @Test
    fun `an overload raises InternalServerException with 503`() {
        claudeScenario("""{"otherwise":{"error":{"status":529}}}""")

        val failure = assertThrows<InternalServerException> { client.chat().completions().create(params) }

        assertEquals(503, failure.statusCode())
    }

    @Test
    fun `an in-band error after content raises SseException`() {
        claudeScenario("""{"otherwise":{"error":{"status":529},"fault":{"kind":"stream_error","afterPieces":1}}}""")

        val failure = assertThrows<SseException> { chunks() }

        assertEquals("provider_unavailable", failure.code().orElseThrow())
    }

    private companion object {
        const val TOOLS =
            """{"otherwise":{"content":{"mode":"fixed","text":"Checking.","toolCalls":[
            {"id":"toolu_1","name":"get_weather","input":{"city":"Paris"},"chunkChars":4},
            {"id":"toolu_2","name":"get_time","input":{}}]}}}"""
    }
}
