package io.github.pdroacordi.llmgateway.gateway.core.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class EmbeddingReplySizeTest {
    private fun size(
        input: EmbeddingInput = EmbeddingInput.Text("a"),
        dimensions: JsonValue? = null,
        format: JsonValue? = null,
        limits: EmbeddingReplyLimits = LIMITS,
    ): Long {
        val extras =
            buildMap {
                dimensions?.let { put("dimensions", it) }
                format?.let { put("encoding_format", it) }
            }
        return worstCaseReplyBytes(EmbeddingRequest(ModelName("m"), input, JsonValue.Object(extras)), limits)
    }

    private fun texts(count: Int) = EmbeddingInput.Texts(List(count) { "a" })

    @Test
    fun `2048 by 3072 in base64 is ADR-GW-05-006's 32 MiB`() {
        assertEquals(33_554_432, size(texts(2048), JsonValue.Number("3072"), BASE64))
    }

    @Test
    fun `2048 by 3072 in float is ADR-GW-05-006's 198 MiB`() {
        assertEquals(207_618_048, size(texts(2048), JsonValue.Number("3072"), FLOAT))
    }

    @Test
    fun `an absent encoding_format is float, the API's default`() {
        assertEquals(10 * 33, size(dimensions = JsonValue.Number("10")).toInt())
    }

    @Test
    fun `base64 rounds up, so a reservation is never short`() {
        assertEquals(6, size(dimensions = JsonValue.Number("1"), format = BASE64))
    }

    @Test
    fun `absent dimensions take the configured default`() {
        assertEquals(3072L * 33, size())
    }

    @Test
    fun `each input form counts its items`() {
        val perItem = 3072L * 33

        assertEquals(perItem, size(EmbeddingInput.Text("abc")))
        assertEquals(3 * perItem, size(texts(3)))
        assertEquals(perItem, size(EmbeddingInput.TokenIds(longArrayOf(1, 2, 3, 4, 5))))
        assertEquals(2 * perItem, size(EmbeddingInput.TokenIdLists(listOf(longArrayOf(1), longArrayOf(2)))))
    }

    @Test
    fun `an empty input reserves nothing`() {
        assertEquals(0, size(texts(0)))
    }

    @Test
    fun `the result is capped at max-response-size`() {
        val limits = LIMITS.copy(maxResponseBytes = 1024)

        assertEquals(1024, size(texts(2048), limits = limits))
    }

    @Test
    fun `dimensions at the top of Long's range cannot overflow past the cap`() {
        assertEquals(CAP.toLong(), size(texts(2048), JsonValue.Number(Long.MAX_VALUE.toString())))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "1.5", "1e3", "99999999999999999999999"])
    fun `a dimensions the provider will refuse takes the default`(literal: String) {
        assertEquals(3072L * 33, size(dimensions = JsonValue.Number(literal)))
    }

    @Test
    fun `a dimensions of the wrong type takes the default`() {
        assertEquals(3072L * 33, size(dimensions = JsonValue.Text("10")))
    }

    @Test
    fun `an unknown encoding_format is priced as the widest`() {
        val bytes = size(dimensions = JsonValue.Number("10"), format = JsonValue.Text("binary"))

        assertEquals(10L * 33, bytes)
    }

    private companion object {
        const val CAP = 256 * 1024 * 1024
        val LIMITS = EmbeddingReplyLimits(defaultDimensions = 3072, maxResponseBytes = CAP)
        val BASE64 = JsonValue.Text("base64")
        val FLOAT = JsonValue.Text("float")
    }
}
