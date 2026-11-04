package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AnthropicResponseReaderTest {
    private val mapper = JsonMapper.builder().build()
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_793_750_400), ZoneOffset.UTC)
    private val reader = AnthropicResponseReader(mapper.exactTreeReader(), mapper, clock)

    private fun read(body: String): ProviderResult.Completed? = reader.read(body.encodeToByteArray())

    private fun choice(
        content: String,
        stopReason: String = "\"end_turn\"",
    ): ChatChoice =
        checkNotNull(
            read(
                """{"id":"msg_1","type":"message","model":"m","content":$content,"stop_reason":$stopReason,
                "usage":{"input_tokens":1,"output_tokens":2}}""",
            ),
        ).completion.choices.single()

    @Test
    fun `id and model are unchanged, created comes from the clock`() {
        val completion =
            checkNotNull(
                read("""{"id":"msg_1","model":"m","content":[],"stop_reason":"end_turn"}"""),
            ).completion

        assertEquals("msg_1", completion.id)
        assertEquals(ModelName("m"), completion.model)
        assertEquals(1_793_750_400, completion.created)
    }

    @Test
    fun `text blocks are concatenated`() {
        val content = """[{"type":"text","text":"a"},{"type":"text","text":"b"}]"""

        assertEquals("ab", choice(content).message.content)
    }

    @Test
    fun `refusal and logprobs are null members, so strict SDK shapes hold`() {
        val choice = choice("""[{"type":"text","text":"a"}]""")

        assertEquals(JsonValue.Null, choice.message.extras.members["refusal"])
        assertEquals(JsonValue.Null, choice.extras.members["logprobs"])
    }

    @Test
    fun `tool_use blocks become tool_calls with compact arguments, and null content with no text`() {
        val content = """[{"type":"tool_use","id":"t1","name":"g","input":{"a": 1.50, "b":[1, 2]}}]"""
        val message = choice(content, "\"tool_use\"").message
        val call = (message.extras.members["tool_calls"] as JsonValue.Array).items.single() as JsonValue.Object
        val function = call.members["function"] as JsonValue.Object

        assertNull(message.content)
        assertEquals(JsonValue.Text("t1"), call.members["id"])
        assertEquals(JsonValue.Text("function"), call.members["type"])
        assertEquals(JsonValue.Text("g"), function.members["name"])
        assertEquals(JsonValue.Text("""{"a":1.50,"b":[1,2]}"""), function.members["arguments"])
    }

    @Test
    fun `no text and no tools give an empty string`() {
        assertEquals("", choice("[]").message.content)
    }

    @Test
    fun `thinking blocks stay out of content and go verbatim, in order, to llmgw`() {
        val content =
            """[{"type":"thinking","thinking":"secret","signature":"s"},{"type":"redacted_thinking","data":"r"},
            {"type":"text","text":"answer"}]"""
        val message = choice(content).message
        val llmgw = message.extras.members["llmgw"] as JsonValue.Object
        val blocks = llmgw.members["thinking_blocks"] as JsonValue.Array

        assertEquals("answer", message.content)
        assertEquals(2, blocks.items.size)
        assertEquals(
            JsonValue.Text("s"),
            (blocks.items[0] as JsonValue.Object).members["signature"],
        )
        assertEquals(JsonValue.Text("r"), (blocks.items[1] as JsonValue.Object).members["data"])
    }

    @Test
    fun `any other block type is skipped`() {
        val content = """[{"type":"server_tool_use","id":"x"},{"type":"text","text":"a"}]"""

        assertEquals("a", choice(content).message.content)
    }

    @Test
    fun `stop_details, container and stop_sequence are not relayed`() {
        val completion =
            checkNotNull(
                read(
                    """{"id":"i","model":"m","content":[],"stop_reason":"stop_sequence","stop_sequence":"x",
                    "stop_details":{"a":1},"container":{"id":"c"},"diagnostics":{}}""",
                ),
            ).completion

        assertEquals(emptyMap<String, JsonValue>(), completion.extras.members)
        assertFalse(
            "x" in
                completion.choices
                    .single()
                    .message.extras.members.keys,
        )
    }

    @ParameterizedTest
    @CsvSource(
        "end_turn, stop",
        "stop_sequence, stop",
        "max_tokens, length",
        "model_context_window_exceeded, length",
        "pause_turn, length",
        "tool_use, tool_calls",
        "refusal, content_filter",
        "some_new_reason, stop",
    )
    fun `stop reasons map`(
        stopReason: String,
        finishReason: String,
    ) {
        assertEquals(finishReason, choice("[]", "\"$stopReason\"").finishReason?.value)
    }

    @Test
    fun `a null stop_reason is stop`() {
        assertEquals("stop", choice("[]", "null").finishReason?.value)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not json",
            "[]",
            """{"id":"i","model":"m"}""",
            """{"id":1,"model":"m","content":[]}""",
            """{"id":"i","model":" ","content":[]}""",
            """{"id":"i","content":[]}""",
            """{"id":"i","model":"m","content":"text"}""",
            """{"id":"i","model":"m","content":[{"type":"tool_use","name":"g"}]}""",
        ],
    )
    fun `a body that is not a message is null`(body: String) {
        assertNull(read(body))
    }

    @Test
    fun `usage is read beside the completion, and malformed usage counts as omitted`() {
        val body = """{"id":"i","model":"m","content":[],"usage":{"input_tokens":"x","output_tokens":1}}"""

        assertNull(checkNotNull(read(body)).usage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["cache_read_input_tokens", "cache_creation_input_tokens"])
    fun `an unreadable cache count counts as omitted`(name: String) {
        val body = """{"id":"i","model":"m","content":[],"usage":{"input_tokens":1,"output_tokens":1,"$name":"x"}}"""

        assertNull(checkNotNull(read(body)).usage)
    }
}
