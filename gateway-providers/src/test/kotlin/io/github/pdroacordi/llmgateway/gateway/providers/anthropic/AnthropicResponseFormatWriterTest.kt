package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class AnthropicResponseFormatWriterTest {
    private val mapper = JsonMapper.builder().build()
    private val trees = mapper.exactTreeReader()

    private class Written(
        val json: String,
        val refusal: ProviderFailure.UnsupportedByProvider?,
    )

    private fun write(format: String?): Written {
        val extras = if (format == null) "{}" else """{"response_format":$format}"""
        val members = (trees.readTree(extras).toJsonValue() as JsonValue.Object).present()
        val refusals = Refusals()
        val body = mapper.nodeFactory.objectNode()
        AnthropicResponseFormatWriter(mapper.nodeFactory, refusals).write(members, body)
        return Written(mapper.writeValueAsString(body), refusals.first)
    }

    private fun assertNothingWritten(format: String?) {
        val written = write(format)

        assertEquals("{}", written.json)
        assertNull(written.refusal)
    }

    private fun schemaFormat(jsonSchema: String) = """{"type":"json_schema","json_schema":$jsonSchema}"""

    private fun assertForwardsSchema(format: String) {
        val written = write(format)

        assertNull(written.refusal)
        assertEquals("""{"output_config":{"format":{"type":"json_schema","schema":$SCHEMA}}}""", written.json)
    }

    @Test
    fun `an absent member writes nothing`() {
        assertNothingWritten(null)
    }

    @Test
    fun `a null member writes nothing`() {
        assertNothingWritten("null")
    }

    @Test
    fun `text is the default, so no output_config is written`() {
        assertNothingWritten("""{"type":"text"}""")
    }

    @Test
    fun `a nested member set to null counts as absent beside text`() {
        assertNothingWritten("""{"type":"text","x":null,"json_schema":null}""")
    }

    @Test
    fun `json_schema becomes output_config format with the schema not altered, order and literals kept`() {
        assertForwardsSchema(schemaFormat("""{"schema":$SCHEMA}"""))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"name":"n","description":"d","strict":true,"schema":$SCHEMA}""",
            """{"name":7,"description":{"a":1},"strict":"yes","schema":$SCHEMA}""",
            """{"strict":false,"schema":$SCHEMA}""",
        ],
    )
    fun `name, description and strict are dropped whatever their value`(jsonSchema: String) {
        assertForwardsSchema(schemaFormat(jsonSchema))
    }

    @Test
    fun `a nested member set to null counts as absent beside a schema`() {
        assertForwardsSchema(
            """{"type":"json_schema","x":null,"json_schema":{"y":null,"name":null,"schema":$SCHEMA}}""",
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"type":"json_schema"} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":null} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":"s"} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":[]} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":{}} | invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"name":"n"}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"schema":null}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"schema":"s"}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"schema":[]}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"schema":true}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_object"} | unsupported_value | response_format.type""",
            """{"type":"xml"} | unsupported_value | response_format.type""",
            """{} | unsupported_value | response_format.type""",
            """{"type":null} | unsupported_value | response_format.type""",
            """{"type":7} | unsupported_value | response_format.type""",
            """{"type":["text"]} | unsupported_value | response_format.type""",
            """"text" | unsupported_value | response_format""",
            """7 | unsupported_value | response_format""",
            """true | unsupported_value | response_format""",
            """[] | unsupported_value | response_format""",
            """{"type":"text","extra":1} | unsupported_parameter | response_format.extra""",
            """{"type":"text","json_schema":{"schema":{}}} | unsupported_parameter | response_format.json_schema""",
            """{"type":"json_schema","extra":1,"json_schema":{"schema":{}}} | """ +
                """unsupported_parameter | response_format.extra""",
            """{"type":"json_schema","json_schema":{"schema":{},"extra":1}} | """ +
                """unsupported_parameter | response_format.json_schema.extra""",
        ],
    )
    fun `each refusal row gives its code and param`(
        format: String,
        code: String,
        param: String,
    ) {
        assertEquals(ProviderFailure.UnsupportedByProvider(code, param), write(format).refusal)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"type":"text","bad name!":1}""",
            """{"type":"json_schema","json_schema":{"schema":{},"bad name!":1}}""",
        ],
    )
    fun `a member name that fails the character rule refuses with no param`(format: String) {
        assertEquals(ProviderFailure.UnsupportedByProvider("unsupported_parameter", null), write(format).refusal)
    }

    private companion object {
        const val SCHEMA =
            """{"type":"object","properties":{"z":{"type":"number","default":1.10},"a":{"default":null}},""" +
                """"required":["z","a"],"additionalProperties":false}"""
    }
}
