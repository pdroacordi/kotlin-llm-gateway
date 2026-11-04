package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class AnthropicUsageReaderTest {
    private val mapper = JsonMapper.builder().build()

    private fun read(json: String): Usage? = AnthropicUsageReader.read(mapper.readTree(json))

    private fun Usage.detail(
        member: String,
        name: String,
    ): JsonValue? = (extras.members[member] as JsonValue.Object).members[name]

    @Test
    fun `input is the sum of input, cache read and cache creation, and cached is reads only`() {
        val usage =
            checkNotNull(
                read(
                    """{"input_tokens":10,"cache_read_input_tokens":20,"cache_creation_input_tokens":30,
                    "output_tokens":5}""",
                ),
            )

        assertEquals(TokenCount(60), usage.inputTokens)
        assertEquals(TokenCount(5), usage.outputTokens)
        assertEquals(TokenCount(65), usage.totalTokens)
        assertEquals(TokenCount(20), usage.cachedInputTokens)
        assertFalse(usage.estimated)
    }

    @Test
    fun `details are extras in the OpenAI shape, with creation tokens as an extension`() {
        val usage =
            checkNotNull(
                read(
                    """{"input_tokens":1,"cache_read_input_tokens":2,"cache_creation_input_tokens":3,"output_tokens":4,
                    "output_tokens_details":{"thinking_tokens":2}}""",
                ),
            )

        assertEquals(JsonValue.Number("2"), usage.detail("prompt_tokens_details", "cached_tokens"))
        assertEquals(JsonValue.Number("2"), usage.detail("completion_tokens_details", "reasoning_tokens"))
        assertEquals(JsonValue.Number("3"), usage.extras.members["cache_creation_input_tokens"])
    }

    @Test
    fun `a null cache count is 0, and no thinking detail is 0`() {
        val usage =
            checkNotNull(
                read(
                    """{"input_tokens":10,"cache_read_input_tokens":null,"cache_creation_input_tokens":null,
                    "output_tokens":5}""",
                ),
            )

        assertEquals(TokenCount(10), usage.inputTokens)
        assertEquals(JsonValue.Number("0"), usage.detail("completion_tokens_details", "reasoning_tokens"))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"output_tokens":1}""",
            """{"input_tokens":1}""",
            """{"input_tokens":"1","output_tokens":1}""",
            """{"input_tokens":1,"output_tokens":-1}""",
            """{"input_tokens":1.5,"output_tokens":1}""",
            """{"input_tokens":9223372036854775807,"cache_read_input_tokens":1,"output_tokens":1}""",
            """{"input_tokens":9223372036854775807,"output_tokens":1}""",
            """{"input_tokens":99999999999999999999,"output_tokens":1}""",
            """{"input_tokens":1,"cache_read_input_tokens":"x","output_tokens":1}""",
            """{"input_tokens":1,"cache_read_input_tokens":-1,"output_tokens":1}""",
            """{"input_tokens":1,"cache_read_input_tokens":1.5,"output_tokens":1}""",
            """{"input_tokens":1,"cache_creation_input_tokens":"x","output_tokens":1}""",
            """{"input_tokens":1,"cache_creation_input_tokens":99999999999999999999,"output_tokens":1}""",
            """null""",
            """[]""",
        ],
    )
    fun `missing, unreadable or overflowing counts are omitted usage`(json: String) {
        assertNull(read(json))
    }

    @Test
    fun `absent cache counts are 0`() {
        val usage = checkNotNull(read("""{"input_tokens":10,"output_tokens":5}"""))

        assertEquals(TokenCount(10), usage.inputTokens)
        assertEquals(TokenCount(0), usage.cachedInputTokens)
    }

    @Test
    fun `on a stream a count's last value decides whether it is readable`() {
        val good = mapper.readTree("""{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3}""")
        val bad = mapper.readTree("""{"output_tokens":"x","cache_read_input_tokens":"x"}""")

        assertNull(AnthropicUsageReader.usage(UsageCounts().updatedBy(good).updatedBy(bad)))
        assertEquals(
            TokenCount(2),
            AnthropicUsageReader.usage(UsageCounts().updatedBy(bad).updatedBy(good))?.outputTokens,
        )
    }

    @Test
    fun `absent usage is omitted`() {
        assertNull(AnthropicUsageReader.read(null))
    }

    @Test
    fun `on a stream the last value of each count wins`() {
        val start = mapper.readTree("""{"input_tokens":10,"cache_read_input_tokens":20,"output_tokens":1}""")
        val delta = mapper.readTree("""{"output_tokens":5,"input_tokens":null}""")
        val counts = UsageCounts().updatedBy(start).updatedBy(delta)

        assertEquals(UsageCounts(input = 10, output = 5, cacheRead = 20), counts)
    }
}
