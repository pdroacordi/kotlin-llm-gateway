package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UsageEstimatorTest {
    private val estimator = UsageEstimator()

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

    private fun request(content: MessageContent?): ChatRequest =
        ChatRequest(ModelName("m"), listOf(ChatMessage(Role("user"), content)))

    private fun completion(text: String): ChatCompletion =
        ChatCompletion("id", 1, ModelName("m"), listOf(ChatChoice(0, AssistantMessage(text), FinishReason("stop"))))
}
