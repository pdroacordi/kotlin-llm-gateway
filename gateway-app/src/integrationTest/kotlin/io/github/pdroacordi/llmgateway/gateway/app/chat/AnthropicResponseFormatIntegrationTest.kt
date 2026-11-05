package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode

/** The wire only: the mock does not constrain output, so nothing here proves conformance (ADR-GW-06-005). */
class AnthropicResponseFormatIntegrationTest : AnthropicBackedTest() {
    private fun request(
        format: String,
        extra: String = "",
    ): String =
        """{"model":"mock-claude","response_format":$format$extra,"messages":[{"role":"user","content":"Hi"}]}"""

    private fun rawBody(requestId: String): String = journalOf(requestId).single().at("/body").stringValue()

    private fun assertSchemaSent(requestId: String) {
        val sent = upstreamBody(requestId)
        val format = sent.at("/output_config/format")

        assertEquals(setOf("type", "schema"), format.propertyNames().toSet())
        assertEquals("json_schema", format.get("type").stringValue())
        assertTrue(""""schema":$SCHEMA""" in rawBody(requestId)) { rawBody(requestId) }
        assertNull(sent.get("response_format"))
    }

    private fun assertNoOutputConfig(sent: JsonNode) {
        assertNull(sent.get("output_config")) { sent.toString() }
        assertNull(sent.get("response_format")) { sent.toString() }
    }

    private fun assertToolAndSchemaSent(requestId: String) {
        assertSchemaSent(requestId)
        assertEquals("get_weather", upstreamBody(requestId).at("/tools/0/name").stringValue())
    }

    @Test
    fun `json_schema reaches the journal as output_config format with the same schema, on complete`() {
        assertSchemaSent(answer(request(JSON_SCHEMA)).requestId)
    }

    @Test
    fun `json_schema reaches the journal as output_config format with the same schema, on stream`() {
        val stream = rawStream(request(JSON_SCHEMA, STREAMING))

        assertEquals(200, stream.status) { stream.body }
        assertSchemaSent(stream.requestId)
    }

    @Test
    fun `text puts no output_config in the body, on complete and on stream`() {
        assertNoOutputConfig(upstreamBody(answer(request(TEXT)).requestId))
        assertNoOutputConfig(upstreamBody(rawStream(request(TEXT, STREAMING)).requestId))
    }

    @Test
    fun `json_schema with tools carries both, on complete and on stream`() {
        assertToolAndSchemaSent(answer(request(JSON_SCHEMA, WITH_TOOLS)).requestId)
        assertToolAndSchemaSent(rawStream(request(JSON_SCHEMA, WITH_TOOLS + STREAMING)).requestId)
    }

    @Test
    fun `the JSON arrives in a text block, as message content and delta content`() {
        claudeScenario("""{"otherwise":{"content":{"mode":"fixed","text":"{\"city\":\"Paris\"}"}}}""")

        val complete = answer(request(JSON_SCHEMA)).body
        val stream = rawStream(request(JSON_SCHEMA, STREAMING))
        val streamed =
            stream
                .chunks()
                .joinToString("") { it.at("/choices/0/delta/content").stringValue("") }

        assertEquals("""{"city":"Paris"}""", complete.at("/choices/0/message/content").stringValue())
        assertEquals("""{"city":"Paris"}""", streamed)
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"type":"json_object"} | unsupported_value | response_format.type""",
            """{"type":"xml"} | unsupported_value | response_format.type""",
            """{} | unsupported_value | response_format.type""",
            """"text" | unsupported_value | response_format""",
            """{"type":"json_schema"} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":7} | invalid_request | response_format.json_schema""",
            """{"type":"json_schema","json_schema":{"name":"n"}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"json_schema","json_schema":{"schema":7}} | """ +
                """invalid_request | response_format.json_schema.schema""",
            """{"type":"text","extra":1} | unsupported_parameter | response_format.extra""",
            """{"type":"json_schema","json_schema":{"schema":{},"extra":1}} | """ +
                """unsupported_parameter | response_format.json_schema.extra""",
        ],
    )
    fun `a refused form gives 400 with its code and param, on both forms, and no upstream call`(
        format: String,
        code: String,
        param: String,
    ) {
        val before = lastSeq()

        listOf(request(format), request(format, STREAMING)).forEach { body ->
            val result = chat(body).result()
            val error = mapper.readTree(result.responseBody).get("error")

            assertEquals(400, result.status.value()) { result.responseBody }
            assertEquals("invalid_request_error", error.get("type").stringValue())
            assertEquals(code, error.get("code").stringValue())
            assertEquals(param, error.get("param").stringValue())
        }
        assertEquals(before, lastSeq())
    }

    @Test
    fun `client output_config is still 400 unsupported_parameter, and no upstream call`() {
        val before = lastSeq()
        val body = """{"model":"mock-claude","output_config":{"format":{"type":"json_schema","schema":$SCHEMA}},
            "messages":[{"role":"user","content":"Hi"}]}"""

        val result = chat(body).result()
        val error = mapper.readTree(result.responseBody).get("error")

        assertEquals(400, result.status.value()) { result.responseBody }
        assertEquals("unsupported_parameter", error.get("code").stringValue())
        assertEquals("output_config", error.get("param").stringValue())
        assertEquals(before, lastSeq())
    }

    @Test
    fun `a schema the provider refuses is relayed with the fixed message, on both forms`() {
        claudeScenario("""{"otherwise":{"error":{"status":400,"message":"$PROVIDER_REASON"}}}""")

        listOf(request(JSON_SCHEMA), request(JSON_SCHEMA, STREAMING)).forEach { body ->
            val result = chat(body).result()
            val error = mapper.readTree(result.responseBody).get("error")

            assertEquals(400, result.status.value()) { result.responseBody }
            assertEquals("upstream_rejected_request", error.get("code").stringValue())
            assertEquals(
                "The provider rejected the request; see the code and param members.",
                error.get("message").stringValue(),
            )
            assertFalse(PROVIDER_REASON in checkNotNull(result.responseBody)) { result.responseBody }
        }
    }

    private companion object {
        const val SCHEMA =
            """{"type":"object","properties":{"z":{"type":"number","default":1.10},"a":{"default":null}},""" +
                """"required":["z","a"],"additionalProperties":false}"""
        const val JSON_SCHEMA =
            """{"type":"json_schema","json_schema":{"name":"reply","description":"d","strict":true,"schema":$SCHEMA}}"""
        const val TEXT = """{"type":"text"}"""
        const val STREAMING = ""","stream":true"""
        const val WITH_TOOLS =
            ""","tools":[{"type":"function","function":{"name":"get_weather",
            "parameters":{"type":"object","properties":{"city":{"type":"string"}}}}}]"""
        const val PROVIDER_REASON = "schema too complex"
    }
}
