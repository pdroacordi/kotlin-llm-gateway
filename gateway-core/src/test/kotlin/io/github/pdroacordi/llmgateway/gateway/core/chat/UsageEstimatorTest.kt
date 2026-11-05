package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageEstimatorTest {
    private val estimator = UsageEstimator()

    @Test
    fun `a text input counts its length over 4, rounded up, with no message overhead`() {
        assertEquals(TokenCount(2), estimator.estimate(EmbeddingInput.Text("12345")).inputTokens)
    }

    @Test
    fun `each text of a list rounds up on its own`() {
        assertEquals(TokenCount(4), estimator.estimate(EmbeddingInput.Texts(listOf("1", "2", "12345"))).inputTokens)
    }

    @Test
    fun `a token array counts its ids`() {
        assertEquals(TokenCount(3), estimator.estimate(EmbeddingInput.TokenIds(longArrayOf(7, 8, 9))).inputTokens)
    }

    @Test
    fun `token arrays sum their ids`() {
        val input = EmbeddingInput.TokenIdLists(listOf(longArrayOf(7), longArrayOf(8, 9)))

        assertEquals(TokenCount(3), estimator.estimate(input).inputTokens)
    }

    @Test
    fun `an embedding estimate has no output and is flagged`() {
        val usage = estimator.estimate(EmbeddingInput.Texts(emptyList()))

        assertEquals(TokenCount(0), usage.inputTokens)
        assertEquals(TokenCount(0), usage.outputTokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `a text counts its length over 4, rounded up, plus 3 per message and 3 for the reply`() {
        val usage = estimator.estimate(request(MessageContent.Text("12345")), completion("12345678"))

        // ceil(5 / 4) + 3 per message + 3 for the reply; ceil(8 / 4).
        assertEquals(TokenCount(8), usage.inputTokens)
        assertEquals(TokenCount(2), usage.outputTokens)
    }

    @Test
    fun `the result is flagged estimated and has no cached count`() {
        val usage = estimator.estimate(request(MessageContent.Text("a")), completion("b"))

        assertTrue(usage.estimated)
        assertNull(usage.cachedInputTokens)
    }

    @Test
    fun `each text part rounds up on its own and an opaque part counts 0`() {
        val parts =
            MessageContent.Parts(
                listOf(
                    ContentPart.Text("a"),
                    ContentPart.Text("b"),
                    ContentPart.Opaque(JsonValue.Object(mapOf("type" to JsonValue.Text("image_url")))),
                ),
            )

        assertEquals(TokenCount(8), estimator.estimate(request(parts), completion("")).inputTokens)
    }

    @Test
    fun `a message with no content still costs its overhead`() {
        assertEquals(TokenCount(6), estimator.estimate(request(null), completion("")).inputTokens)
    }

    @Test
    fun `output sums every choice and null content counts 0`() {
        val completion =
            ChatCompletion(
                "id",
                1,
                ModelName("m"),
                listOf(
                    ChatChoice(0, AssistantMessage("12345"), FinishReason("stop")),
                    ChatChoice(1, AssistantMessage(null), FinishReason("tool_calls")),
                    ChatChoice(2, AssistantMessage("1"), null),
                ),
            )

        assertEquals(TokenCount(3), estimator.estimate(request(null), completion).outputTokens)
    }

    @Test
    fun `every message adds its overhead`() {
        val request =
            ChatRequest(
                ModelName("m"),
                listOf(ChatMessage(Role("system"), null), ChatMessage(Role("user"), MessageContent.Text("1234"))),
            )

        assertEquals(TokenCount(10), estimator.estimate(request, completion("")).inputTokens)
    }

    @Test
    fun `the character estimate equals the non-stream estimate for the same text`() {
        val request = request(MessageContent.Text("12345"))
        val completion =
            ChatCompletion(
                "id",
                1,
                ModelName("m"),
                listOf(
                    ChatChoice(0, AssistantMessage("12345"), FinishReason("stop")),
                    ChatChoice(1, AssistantMessage("1"), FinishReason("stop")),
                ),
            )

        assertEquals(estimator.estimate(request, completion), estimator.estimate(request, listOf(5L, 1L)))
    }

    @Test
    fun `no output characters count 0 and the result is flagged estimated`() {
        val usage = estimator.estimate(request(null), emptyList<Long>())

        assertEquals(TokenCount(0), usage.outputTokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `a tool-only reply counts the function name and arguments, flagged estimated`() {
        val usage = estimator.estimate(request(null), toolReply(null, WEATHER))

        // ceil((11 + 16) / 4).
        assertEquals(TokenCount(7), usage.outputTokens)
        assertTrue(usage.estimated)
    }

    @Test
    fun `text and tool calls round once per choice`() {
        // ceil((3 + 27) / 4), where rounding text and tools apart would give 1 + 7.
        assertEquals(TokenCount(8), estimator.estimate(request(null), toolReply("abc", WEATHER)).outputTokens)
    }

    @Test
    fun `two calls of one choice round once`() {
        // ceil((3 + 3) / 4), where rounding each call would give 2.
        val completion = toolReply(null, call("a", "12"), call("b", "12"))

        assertEquals(TokenCount(2), estimator.estimate(request(null), completion).outputTokens)
    }

    @Test
    fun `two choices round apart`() {
        val message = AssistantMessage(null, toolCalls(call("a", "1")))
        val completion =
            ChatCompletion(
                "id",
                1,
                ModelName("m"),
                listOf(
                    ChatChoice(0, message, FinishReason("tool_calls")),
                    ChatChoice(1, message, FinishReason("tool_calls")),
                ),
            )

        assertEquals(TokenCount(2), estimator.estimate(request(null), completion).outputTokens)
    }

    @Test
    fun `an id, a type and any other member of a call count nothing`() {
        val function = obj("name" to text("a"), "arguments" to text("1"), "y" to text("long"))
        val call = obj("id" to text("call_1"), "type" to text("function"), "x" to text("long"), "function" to function)

        assertEquals(2L, toolCallCharacters(toolCalls(call)))
    }

    @Test
    fun `a name with arguments of another type counts the name alone`() {
        val call = obj("function" to obj("name" to text("a"), "arguments" to obj("city" to text("Paris"))))

        assertEquals(1L, toolCallCharacters(toolCalls(call)))
    }

    @Test
    fun `arguments with a name of another type count the arguments alone`() {
        val call = obj("function" to obj("name" to JsonValue.Number("7"), "arguments" to text("12")))

        assertEquals(2L, toolCallCharacters(toolCalls(call)))
    }

    @Test
    fun `a tool_calls member of the wrong shape counts 0 and raises nothing`() {
        val shapes =
            listOf(
                JsonValue.Null,
                JsonValue.Number("7"),
                text("get_weather"),
                JsonValue.Object.EMPTY,
                JsonValue.Array(listOf(JsonValue.Number("3"), text("get_weather"), JsonValue.Null)),
                JsonValue.Array(listOf(JsonValue.Object.EMPTY)),
                JsonValue.Array(listOf(obj("function" to JsonValue.Null))),
                JsonValue.Array(listOf(obj("function" to text("get_weather")))),
            )

        shapes.forEach { shape -> assertEquals(0L, toolCallCharacters(obj("tool_calls" to shape))) { "$shape" } }
    }

    @Test
    fun `no tool_calls member counts 0`() {
        assertEquals(0L, toolCallCharacters(JsonValue.Object.EMPTY))
    }

    private fun toolReply(
        content: String?,
        vararg calls: JsonValue,
    ): ChatCompletion =
        ChatCompletion(
            "id",
            1,
            ModelName("m"),
            listOf(ChatChoice(0, AssistantMessage(content, toolCalls(*calls)), FinishReason("tool_calls"))),
        )

    private fun toolCalls(vararg calls: JsonValue): JsonValue.Object =
        obj("tool_calls" to JsonValue.Array(calls.toList()))

    private fun call(
        name: String,
        arguments: String,
    ): JsonValue.Object = obj("function" to obj("name" to text(name), "arguments" to text(arguments)))

    private fun obj(vararg members: Pair<String, JsonValue>): JsonValue.Object = JsonValue.Object(mapOf(*members))

    private fun text(value: String): JsonValue = JsonValue.Text(value)

    private fun request(content: MessageContent?): ChatRequest =
        ChatRequest(ModelName("m"), listOf(ChatMessage(Role("user"), content)))

    private fun completion(text: String): ChatCompletion =
        ChatCompletion("id", 1, ModelName("m"), listOf(ChatChoice(0, AssistantMessage(text), FinishReason("stop"))))

    private companion object {
        val WEATHER =
            JsonValue.Object(
                mapOf(
                    "id" to JsonValue.Text("call_1"),
                    "type" to JsonValue.Text("function"),
                    "function" to
                        JsonValue.Object(
                            mapOf(
                                "name" to JsonValue.Text("get_weather"),
                                "arguments" to JsonValue.Text("""{"city":"Paris"}"""),
                            ),
                        ),
                ),
            )
    }
}
