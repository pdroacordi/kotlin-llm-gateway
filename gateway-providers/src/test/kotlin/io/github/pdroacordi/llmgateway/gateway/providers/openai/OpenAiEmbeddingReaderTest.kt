package io.github.pdroacordi.llmgateway.gateway.providers.openai

import com.sun.management.ThreadMXBean
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.lang.management.ManagementFactory

class OpenAiEmbeddingReaderTest {
    private val reader = OpenAiEmbeddingReader(JsonMapper.builder().build().exactTreeReader())

    private fun read(json: String): EmbeddingResult.Embedded? = reader.read(json.encodeToByteArray())

    private fun body(
        item: String = ITEM,
        top: String = "",
    ): String = """{"object":"list","data":[$item],"model":"m","usage":{"prompt_tokens":2,"total_tokens":2}$top}"""

    private fun answered(json: String): EmbeddingResult.Embedded = checkNotNull(read(json))

    @Test
    fun `a reply is read, with the vector's bytes untouched`() {
        val result = answered(body("""{"object":"embedding","index":0,"embedding":[ 1e-5 ,  0.10 ]}"""))

        assertEquals(ModelName("m"), result.list.model)
        val item = result.list.data.single()
        assertEquals(0, item.index)
        assertEquals("[ 1e-5 ,  0.10 ]", item.vector.bytes.decodeToString())
        assertEquals(JsonValue.Object.EMPTY, item.extras)
        assertEquals(JsonValue.Object.EMPTY, result.list.extras)
        assertEquals(TokenCount(2), result.usage?.inputTokens)
    }

    @Test
    fun `a base64 string is kept with its quotes`() {
        val result = answered(body("""{"index":0,"embedding":"AAAAPw=="}"""))

        assertEquals(
            RawJson("\"AAAAPw==\"".encodeToByteArray()),
            result.list.data
                .single()
                .vector,
        )
    }

    @Test
    fun `items keep the provider's order and index`() {
        val result = answered(body("""{"index":1,"embedding":[1]},{"index":0,"embedding":[2]}"""))

        assertEquals(listOf(1, 0), result.list.data.map { it.index })
    }

    @Test
    fun `member order does not matter`() {
        val result = answered("""{"model":"m","data":[{"embedding":[1],"index":3}]}""")

        assertEquals(
            3,
            result.list.data
                .single()
                .index,
        )
    }

    @Test
    fun `a number past BigDecimal's range in a vector stays opaque`() {
        val result = answered(body("""{"index":0,"embedding":[1e99999999999]}"""))

        assertEquals(
            "[1e99999999999]",
            result.list.data
                .single()
                .vector.bytes
                .decodeToString(),
        )
    }

    @Test
    fun `unknown members at the top and in an item go to extras, in order, verbatim`() {
        val result =
            answered(
                body(
                    """{"x_item":{"a":[1,null]},"index":0,"embedding":[1],"y":"é"}""",
                    ""","x_top":[true],"id":"embd-1" """.trimEnd(),
                ),
            )

        assertEquals(
            JsonValue.Object(
                linkedMapOf(
                    "x_item" to
                        JsonValue.Object(
                            mapOf("a" to JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null))),
                        ),
                    "y" to JsonValue.Text("é"),
                ),
            ),
            result.list.data
                .single()
                .extras,
        )
        assertEquals(
            listOf("x_top", "id"),
            result.list.extras.members.keys
                .toList(),
        )
    }

    @Test
    fun `object at either level is not kept, and a top-level llmgw is dropped`() {
        val result = answered(body("""{"object":"other","index":0,"embedding":[1]}""", ""","llmgw":{"a":1}"""))

        assertEquals(JsonValue.Object.EMPTY, result.list.extras)
        assertEquals(
            JsonValue.Object.EMPTY,
            result.list.data
                .single()
                .extras,
        )
    }

    @Test
    fun `an unknown member in usage reaches usage extras`() {
        val result =
            answered("""{"data":[$ITEM],"model":"m","usage":{"prompt_tokens":2,"total_tokens":2,"x_usage":3}}""")

        assertEquals(mapOf("x_usage" to JsonValue.Number("3")), result.usage?.extras?.members)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", ""","usage":null""", ""","usage":5""", ""","usage":{}""", ""","usage":{"prompt_tokens":"2"}"""],
    )
    fun `absent, null or malformed usage is omitted, and the reply stands`(usage: String) {
        val result = answered("""{"data":[$ITEM],"model":"m"$usage}""")

        assertNull(result.usage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "5", "{}", "[]", "\"x\"", "[5]", "[null]", "[[]]"])
    fun `data that is not a non-empty array of objects is unreadable`(data: String) {
        assertNull(read("""{"data":$data,"model":"m"}"""))
    }

    @Test
    fun `an absent data is unreadable`() {
        assertNull(read("""{"model":"m"}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "5", "true", "{}"])
    fun `an embedding that is not an array or a string is unreadable`(vector: String) {
        assertNull(read(body("""{"index":0,"embedding":$vector}""")))
    }

    @Test
    fun `an absent embedding is unreadable`() {
        assertNull(read(body("""{"index":0}""")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "1.0", "1e0", "2147483648", "\"0\""])
    fun `an index that is not an Int is unreadable`(index: String) {
        assertNull(read(body("""{"index":$index,"embedding":[1]}""")))
    }

    @Test
    fun `an absent index is unreadable`() {
        assertNull(read(body("""{"embedding":[1]}""")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["null", "5", "\"\"", "\" \"", "[]"])
    fun `a model that is not a non-blank string is unreadable`(model: String) {
        assertNull(read("""{"data":[$ITEM],"model":$model}"""))
    }

    @Test
    fun `an absent model is unreadable`() {
        assertNull(read("""{"data":[$ITEM]}"""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["[]", "5", "null", "\"x\"", ""])
    fun `a root that is not an object is unreadable`(json: String) {
        assertNull(read(json))
    }

    @Test
    fun `a trailing token after the root is unreadable`() {
        assertNull(read(body() + "x"))
    }

    @Test
    fun `a body that breaks off inside a vector is unreadable`() {
        assertNull(read("""{"data":[{"index":0,"embedding":[1,"""))
    }

    @Test
    fun `an extra with a number past BigDecimal's range is unreadable, with no exception`() {
        assertNull(read(body(top = ""","x":1e99999999999""")))
    }

    @Test
    fun `a UTF-16 body is unreadable`() {
        assertNull(reader.read(body().toByteArray(Charsets.UTF_16)))
    }

    // Without the byte-range scan a float costs a DecimalNode and a BigDecimal, far over this bound. A warm first pass
    // keeps class loading out of the count.
    @Test
    fun `a body of 1,000,000 floats allocates under 3 times its size`() {
        val floats = (0 until FLOATS).joinToString(",") { "0.${1000000 + it}" }
        val body = """{"data":[{"index":0,"embedding":[$floats]}],"model":"m"}""".encodeToByteArray()
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val thread = Thread.currentThread().threadId()
        assertTrue(threads.isThreadAllocatedMemorySupported)
        assertNotNull(reader.read(body))

        val before = threads.getThreadAllocatedBytes(thread)
        val result = reader.read(body)
        val allocated = threads.getThreadAllocatedBytes(thread) - before

        assertEquals(
            body.size - OVERHEAD,
            checkNotNull(result)
                .list.data
                .single()
                .vector.bytes.size,
        )
        assertTrue(allocated < 3L * body.size) { "allocated $allocated for a body of ${body.size}" }
    }

    @Test
    fun `toString of a result holds no vector`() {
        assertFalse("0.125" in answered(body("""{"index":0,"embedding":[0.125]}""")).toString())
    }

    private companion object {
        const val ITEM = """{"object":"embedding","index":0,"embedding":[1]}"""
        const val FLOATS = 1_000_000

        // The bytes around the vector in that test's body.
        const val OVERHEAD = 47
    }
}
