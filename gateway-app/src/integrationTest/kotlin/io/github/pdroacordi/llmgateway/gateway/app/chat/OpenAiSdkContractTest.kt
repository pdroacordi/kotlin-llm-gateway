package io.github.pdroacordi.llmgateway.gateway.app.chat

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.BadRequestException
import com.openai.errors.InternalServerException
import com.openai.errors.NotFoundException
import com.openai.errors.RateLimitException
import com.openai.errors.UnauthorizedException
import com.openai.helpers.ChatCompletionAccumulator
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import java.time.Duration

/** The official SDK against the gateway and the mock; never a provider, so not a `live` test (ADR-GW-01-009). */
class OpenAiSdkContractTest(
    @Autowired private val context: ApplicationContext,
) : MockBackedTest() {
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
            .build()

    @Test
    fun `the SDK completes a chat and the response passes validate`() {
        val completion = client.chat().completions().create(params)

        completion.validate()
        assertEquals(MODEL, completion.model())
        assertTrue(
            completion
                .choices()
                .single()
                .message()
                .content()
                .isPresent,
        )
        assertTrue(completion.usage().isPresent)
    }

    private fun ChatCompletion.calls(): List<Triple<String, String, String>> =
        choices()
            .single()
            .message()
            .toolCalls()
            .orElseThrow()
            .map { call ->
                val function = call.asFunction()
                Triple(function.id(), function.function().name(), function.function().arguments())
            }

    @Test
    fun `tool calls pass validate, and the accumulator reads the same tool_calls as the reply`() {
        scenario(TOOLS)
        val accumulator = ChatCompletionAccumulator.create()

        val completion = client.chat().completions().create(params)
        client
            .chat()
            .completions()
            .createStreaming(params)
            .use { stream ->
                stream.stream().forEach {
                    it.validate()
                    accumulator.accumulate(it)
                }
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
    fun `withRawResponse sees the gateway's request ID`() {
        client
            .chat()
            .completions()
            .withRawResponse()
            .create(params)
            .use { raw ->
                raw.parse().validate()
                val requestId = raw.requestId().orElseThrow()

                assertEquals(requestId, raw.headers().values("x-request-id").single())
                assertEquals(1, journalOf(requestId).size)
            }
    }

    @Test
    fun `a provider 400 raises BadRequestException`() {
        scenario("""{"otherwise":{"error":{"status":400,"code":"bad_value","param":"seed"}}}""")

        val failure = assertThrows<BadRequestException> { client.chat().completions().create(params) }

        assertEquals(400, failure.statusCode())
        assertEquals("bad_value", failure.code().orElseThrow())
    }

    @Test
    fun `a provider 404 for the model raises NotFoundException`() {
        scenario("""{"otherwise":{"error":{"status":404}}}""")

        assertThrows<NotFoundException> { client.chat().completions().create(params) }
    }

    @Test
    fun `a provider 429 raises RateLimitException`() {
        scenario("""{"otherwise":{"error":{"status":429}}}""")

        assertThrows<RateLimitException> { client.chat().completions().create(params) }
    }

    @Test
    fun `an upstream failure raises InternalServerException`() {
        scenario("""{"otherwise":{"error":{"status":500}}}""")

        val failure = assertThrows<InternalServerException> { client.chat().completions().create(params) }

        assertEquals(502, failure.statusCode())
    }

    @Test
    fun `a wrong key raises the SDK's authentication exception`() {
        val stranger = client.withOptions { it.apiKey(TestKeys.UNKNOWN_KEY) }

        val failure = assertThrows<UnauthorizedException> { stranger.chat().completions().create(params) }

        assertEquals("invalid_api_key", failure.code().orElseThrow())
    }

    @Test
    fun `Jackson 3 stays the app's codec with the SDK's Jackson 2 on the classpath`() {
        assertTrue(context.getBeanNamesForType(tools.jackson.databind.json.JsonMapper::class.java).isNotEmpty())
        assertTrue(
            context.getBeanNamesForType(com.fasterxml.jackson.databind.ObjectMapper::class.java).isEmpty(),
        )
    }

    private companion object {
        const val TOOLS =
            """{"otherwise":{"content":{"mode":"fixed","text":"","toolCalls":[
            {"id":"call_1","name":"get_weather","input":{"city":"Paris"},"chunkChars":3},
            {"id":"call_2","name":"get_time","input":{"zone":"CET"},"chunkChars":3}]}}}"""
    }
}
