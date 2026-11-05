package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class OpenAiEmbeddingUsageReaderTest {
    private val mapper = JsonMapper.builder().build()

    private fun read(json: String) = OpenAiEmbeddingUsageReader.read(mapper.readTree(json))

    @Test
    fun `prompt_tokens is the input, the output is 0, and the count is not flagged`() {
        val usage = checkNotNull(read("""{"prompt_tokens":7,"total_tokens":7}"""))

        assertEquals(TokenCount(7), usage.inputTokens)
        assertEquals(TokenCount(0), usage.outputTokens)
        assertFalse(usage.estimated)
    }

    @Test
    fun `a reply with no completion_tokens is not omitted usage`() {
        assertEquals(TokenCount(7), read("""{"prompt_tokens":7}""")?.inputTokens)
    }

    @Test
    fun `completion_tokens and total_tokens from a provider are not kept`() {
        val usage = checkNotNull(read("""{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}"""))

        assertEquals(TokenCount(0), usage.outputTokens)
        assertEquals(TokenCount(7), usage.totalTokens)
        assertEquals(JsonValue.Object.EMPTY, usage.extras)
    }

    @Test
    fun `another member goes to extras`() {
        val usage = read("""{"prompt_tokens":7,"x_future":{"a":1}}""")

        assertEquals(
            listOf("x_future"),
            usage
                ?.extras
                ?.members
                ?.keys
                ?.toList(),
        )
    }

    @Test
    fun `null is omitted`() {
        assertNull(OpenAiEmbeddingUsageReader.read(null))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{}",
            "null",
            "5",
            "[]",
            """{"prompt_tokens":null}""",
            """{"prompt_tokens":"7"}""",
            """{"prompt_tokens":-1}""",
            """{"prompt_tokens":1.5}""",
            """{"prompt_tokens":9223372036854775808}""",
        ],
    )
    fun `a bad prompt_tokens is omitted usage`(json: String) {
        assertNull(read(json))
    }
}
