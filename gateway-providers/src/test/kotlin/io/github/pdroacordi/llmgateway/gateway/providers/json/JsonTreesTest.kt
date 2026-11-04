package io.github.pdroacordi.llmgateway.gateway.providers.json

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

class JsonTreesTest {
    private val mapper = JsonMapper.builder().build()
    private val reader = mapper.exactTreeReader()
    private val nodes = JsonNodeFactory.instance

    private fun value(json: String): JsonValue = reader.readTree(json).toJsonValue()

    private fun roundTrip(json: String): String = mapper.writeValueAsString(value(json).toJsonNode(nodes))

    @Test
    fun `each JSON kind maps to its JsonValue`() {
        assertEquals(JsonValue.Null, value("null"))
        assertEquals(JsonValue.Bool(true), value("true"))
        assertEquals(JsonValue.Text("a"), value("\"a\""))
        assertEquals(JsonValue.Number("1"), value("1"))
        assertEquals(JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null)), value("[1,null]"))
        assertEquals(JsonValue.Object(mapOf("a" to JsonValue.Bool(false))), value("""{"a":false}"""))
    }

    @Test
    fun `a null member stays and an absent member stays absent`() {
        val members = (value("""{"a":null}""") as JsonValue.Object).members

        assertTrue("a" in members)
        assertFalse("b" in members)
        assertEquals("""{"a":null}""", roundTrip("""{"a":null}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "9007199254740993",
            "-9007199254740993",
            "123456789012345678901234567890",
            "0",
            "0.7",
            "1.0",
            "0.70",
            "0.1234567890123456789012345678",
        ],
    )
    fun `a number keeps its text through the gateway`(literal: String) {
        assertEquals(JsonValue.Number(literal), value(literal))
        assertEquals(literal, roundTrip(literal))
    }

    // BigDecimal prints these in its own form; the value is exact, and the output is still a JSON number.
    @ParameterizedTest
    @CsvSource("1e-5,0.00001", "1E+3,1E+3", "2.5e-7,2.5E-7")
    fun `an exponent keeps its value`(
        literal: String,
        written: String,
    ) {
        assertEquals(written, roundTrip(literal))
    }

    @Test
    fun `nesting and member order survive`() {
        val json = """{"z":{"y":[1,{"x":null,"a":"b"}]},"b":true,"a":[]}"""

        assertEquals(json, roundTrip(json))
    }

    @Test
    fun `text with escapes survives`() {
        assertEquals(JsonValue.Text("a\"b\n"), value(""""a\"b\n""""))
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"a":1} {"b":2}""", """{"a":""", "not json"])
    fun `content after the first value, or no JSON at all, reads as null`(body: String) {
        assertNull(reader.readTreeOrNull(body.encodeToByteArray()))
    }

    // Raised as a NumberFormatException quoting the literal, not as a JacksonException.
    @Test
    fun `a decimal past BigDecimal's range reads as null`() {
        assertNull(reader.readTreeOrNull("""{"a":1e99999999999}""".encodeToByteArray()))
    }

    @Test
    fun `a good body reads as its tree`() {
        assertEquals(JsonValue.Number("1"), reader.readTreeOrNull("1".encodeToByteArray())?.toJsonValue())
    }
}
