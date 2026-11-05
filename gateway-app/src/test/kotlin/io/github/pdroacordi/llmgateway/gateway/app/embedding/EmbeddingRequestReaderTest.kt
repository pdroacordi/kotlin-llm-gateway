package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingRequest
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class EmbeddingRequestReaderTest {
    private fun read(json: String): EmbeddingRequest = EmbeddingRequestReader.read(TREES.readTree(json) as ObjectNode)

    private fun input(json: String): EmbeddingInput = read("""{"model":"m","input":$json}""").input

    private fun rejection(json: String): GatewayError = assertThrows<GatewayErrorException> { read(json) }.error

    @Test
    fun `a string is one text`() {
        val input = input(""""a"""") as EmbeddingInput.Text

        assertEquals("a", input.text)
    }

    @Test
    fun `an array of strings is texts`() {
        val input = input("""["a","b"]""") as EmbeddingInput.Texts

        assertEquals(listOf("a", "b"), input.texts)
    }

    @Test
    fun `an array of integers is token IDs, up to a Long`() {
        val input = input("[1,-2,9223372036854775807]") as EmbeddingInput.TokenIds

        assertArrayEquals(longArrayOf(1, -2, Long.MAX_VALUE), input.ids)
    }

    @Test
    fun `an array of integer arrays is token ID lists`() {
        val input = input("[[1],[2,3]]") as EmbeddingInput.TokenIdLists

        assertEquals(listOf(listOf(1L), listOf(2L, 3L)), input.lists.map { it.toList() })
    }

    @Test
    fun `the provider's rows pass, so OpenAI's rule stays OpenAI's`() {
        assertEquals("", (input("\"\"") as EmbeddingInput.Text).text)
        assertTrue((input("[]") as EmbeddingInput.Texts).texts.isEmpty())
        assertEquals(listOf(""), (input("""[""]""") as EmbeddingInput.Texts).texts)
        assertEquals(0, (input("[[]]") as EmbeddingInput.TokenIdLists).lists.single().size)
    }

    @Test
    fun `every other member goes to extras in order, bad values included`() {
        val request =
            read(
                """{"x_future":{"a":[1,null]},"model":"m","dimensions":"wide","input":"a",""" +
                    """"encoding_format":7,"user":null}""",
            )

        assertEquals(ModelName("m"), request.model)
        assertEquals(
            listOf("x_future", "dimensions", "encoding_format", "user"),
            request.extras.members.keys
                .toList(),
        )
        assertEquals(JsonValue.Text("wide"), request.extras.members["dimensions"])
        assertEquals(JsonValue.Number("7"), request.extras.members["encoding_format"])
        assertEquals(JsonValue.Null, request.extras.members["user"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"input":"a"}""", """{"model":"","input":"a"}""", """{"model":" ","input":"a"}"""])
    fun `an absent or blank model is modelRequired`(json: String) {
        assertEquals(GatewayError.InvalidRequest.modelRequired, rejection(json))
    }

    @ParameterizedTest
    @ValueSource(strings = ["5", "null", "true", "[]", "{}"])
    fun `a model that is not a string is modelRequired`(model: String) {
        assertEquals(GatewayError.InvalidRequest.modelRequired, rejection("""{"model":$model,"input":"a"}"""))
    }

    @Test
    fun `the model is checked before the input`() {
        assertEquals(GatewayError.InvalidRequest.modelRequired, rejection("{}"))
    }

    @Test
    fun `an absent input is inputRequired`() {
        assertEquals(GatewayError.InvalidRequest.inputRequired, rejection("""{"model":"m"}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "null",
            "5",
            "1.5",
            "true",
            "{}",
            """{"a":1}""",
            """[1,"a"]""",
            """["a",1]""",
            """[null]""",
            """["a",null]""",
            """[true]""",
            """[{}]""",
            "[1.5]",
            "[1.0]",
            "[1e2]",
            "[9223372036854775808]",
            "[-9223372036854775809]",
            """[["a"]]""",
            "[[1],2]",
            "[1,[2]]",
            "[[1.5]]",
            "[[9223372036854775808]]",
            "[[[1]]]",
            "[[null]]",
        ],
    )
    fun `an input the gateway cannot type is inputRequired`(input: String) {
        assertEquals(GatewayError.InvalidRequest.inputRequired, rejection("""{"model":"m","input":$input}"""))
    }

    private companion object {
        val TREES = JsonMapper.builder().build().exactTreeReader()
    }
}
