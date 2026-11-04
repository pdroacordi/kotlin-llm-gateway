package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.ContentPart
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.uuid.Uuid

class AnthropicRequestWriterTest {
    private val mapper = JsonMapper.builder().build()
    private val trees = mapper.exactTreeReader()
    private val writer = AnthropicRequestWriter(mapper.nodeFactory, trees, 4096)

    private fun obj(json: String): JsonValue.Object = trees.readTree(json).toJsonValue() as JsonValue.Object

    private fun message(
        role: String,
        content: MessageContent? = null,
        extras: String = "{}",
    ) = ChatMessage(Role(role), content, obj(extras))

    private fun user(text: String = "Hi") = message("user", MessageContent.Text(text))

    private fun call(
        messages: List<ChatMessage> = listOf(user()),
        extras: String = "{}",
    ) = ChatCall(REQUEST_ID, DEPLOYMENT, ChatRequest(ModelName("client-model"), messages, obj(extras)))

    private fun body(
        messages: List<ChatMessage> = listOf(user()),
        extras: String = "{}",
    ): JsonNode = (writer.write(call(messages, extras)) as WrittenRequest.Body).tree

    private fun json(
        messages: List<ChatMessage> = listOf(user()),
        extras: String = "{}",
    ): String = mapper.writeValueAsString(body(messages, extras))

    private fun refused(
        messages: List<ChatMessage> = listOf(user()),
        extras: String = "{}",
    ): ProviderFailure.UnsupportedByProvider = (writer.write(call(messages, extras)) as WrittenRequest.Refused).failure

    private fun messages(vararg items: ChatMessage): String =
        mapper.writeValueAsString(body(items.toList()).get("messages"))

    @Test
    fun `a plain request has the deployment's model and the default cap`() {
        assertEquals(
            """{"model":"provider-model","max_tokens":4096,"messages":[{"role":"user","content":"Hi"}]}""",
            json(),
        )
    }

    @Test
    fun `the stream body adds stream true, and stream_options stays out`() {
        val body = (writer.writeStream(call()) as WrittenRequest.Body).tree

        assertEquals(true, body.get("stream").booleanValue())
        assertNull(body.get("stream_options"))
    }

    @Test
    fun `sampling members are forwarded with their literal, and absent ones stay absent`() {
        val body = json(extras = """{"temperature":0.20,"top_p":1.0,"top_k":5}""")

        assertTrue(""""temperature":0.20,"top_p":1.0,"top_k":5""" in body) { body }
        assertFalse("temperature" in json())
    }

    @Test
    fun `max_completion_tokens wins over max_tokens, and either replaces the default`() {
        assertEquals(7, body(extras = """{"max_completion_tokens":7,"max_tokens":9}""").get("max_tokens").intValue())
        assertEquals(9, body(extras = """{"max_tokens":9}""").get("max_tokens").intValue())
        assertEquals(4096, body(extras = """{"max_tokens":null}""").get("max_tokens").intValue())
    }

    @Test
    fun `stop becomes stop_sequences, a string becoming a one-item array`() {
        assertEquals("""["x"]""", mapper.writeValueAsString(body(extras = """{"stop":"x"}""").get("stop_sequences")))
        assertEquals(
            """["x","y"]""",
            mapper.writeValueAsString(body(extras = """{"stop":["x","y"]}""").get("stop_sequences")),
        )
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["""{"stop":1}""", """{"stop":{"a":1}}""", """{"stop":[1]}"""])
    fun `stop of another type is unsupported_value`(extras: String) {
        assertEquals(ProviderFailure.UnsupportedByProvider("unsupported_value", "stop"), refused(extras = extras))
    }

    @Test
    fun `user is hashed to lowercase hex SHA-256 and safety_identifier wins`() {
        val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        assertEquals(hash, body(extras = """{"user":"abc"}""").get("metadata").get("user_id").stringValue())
        assertEquals(
            hash,
            body(
                extras = """{"user":"other","safety_identifier":"abc"}""",
            ).get("metadata").get("user_id").stringValue(),
        )
    }

    @Test
    fun `metadata from the client is ignored, cache_control is forwarded`() {
        val body = body(extras = """{"metadata":{"a":"b"},"cache_control":{"type":"ephemeral"}}""")

        assertNull(body.get("metadata"))
        assertEquals("ephemeral", body.get("cache_control").get("type").stringValue())
    }

    @Test
    fun `one system text is a string`() {
        val messages = listOf(message("system", MessageContent.Text("be brief")), user())

        assertEquals("be brief", body(messages).get("system").stringValue())
    }

    @Test
    fun `system and developer hoist in order to text blocks, skipping empty text and keeping cache_control`() {
        val part = ContentPart.Text("b", obj("""{"cache_control":{"type":"ephemeral"}}"""))
        val messages =
            listOf(
                message("developer", MessageContent.Text("a")),
                user(),
                message("system", MessageContent.Parts(listOf(part, ContentPart.Text("")))),
                message("system", MessageContent.Text("")),
            )

        assertEquals(
            """[{"type":"text","text":"a"},{"type":"text","text":"b","cache_control":{"type":"ephemeral"}}]""",
            mapper.writeValueAsString(body(messages).get("system")),
        )
    }

    @Test
    fun `nothing left after hoisting is refused on messages`() {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages"),
            refused(listOf(message("system", MessageContent.Text("x")))),
        )
    }

    @Test
    fun `user parts become text blocks`() {
        val parts = listOf(ContentPart.Text("a"), ContentPart.Text("b", obj("""{"cache_control":{"type":"e"}}""")))

        assertEquals(
            """[{"role":"user","content":[{"type":"text","text":"a"},""" +
                """{"type":"text","text":"b","cache_control":{"type":"e"}}]}]""",
            messages(message("user", MessageContent.Parts(parts))),
        )
    }

    @Test
    fun `a non-text part is refused with its path`() {
        val parts = listOf(ContentPart.Text("a"), ContentPart.Opaque(obj("""{"type":"image_url"}""")))

        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", "messages[1].content[1]"),
            refused(listOf(user(), message("user", MessageContent.Parts(parts)))),
        )
    }

    @Test
    fun `name is ignored on any message, another member is refused`() {
        assertEquals(
            """[{"role":"user","content":"Hi"}]""",
            messages(message("user", MessageContent.Text("Hi"), """{"name":"bob"}""")),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", "messages[0].weird"),
            refused(listOf(message("user", MessageContent.Text("Hi"), """{"weird":1}"""))),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", "messages[0].content[0].weird"),
            refused(
                listOf(
                    message("user", MessageContent.Parts(listOf(ContentPart.Text("a", obj("""{"weird":1}"""))))),
                ),
            ),
        )
    }

    @ParameterizedTest
    @CsvSource("function", "critic")
    fun `another role is refused`(role: String) {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_value", "messages[0].role"),
            refused(listOf(message(role, MessageContent.Text("x")))),
        )
    }

    @Test
    fun `an assistant of plain text stays a string`() {
        assertEquals(
            """[{"role":"assistant","content":"ok"}]""",
            messages(message("assistant", MessageContent.Text("ok"))),
        )
    }

    @Test
    fun `an assistant with tool_calls gives text then tool_use, numbers keeping their literal`() {
        val toolCalls =
            """{"tool_calls":[{"id":"t1","type":"function","function":{"name":"get",
            "arguments":"{\"a\":1.50,\"big\":12345678901234567890}"}}]}"""

        assertEquals(
            """[{"role":"assistant","content":[{"type":"text","text":"ok"},""" +
                """{"type":"tool_use","id":"t1","name":"get","input":{"a":1.50,"big":12345678901234567890}}]}]""",
            messages(message("assistant", MessageContent.Text("ok"), toolCalls)),
        )
    }

    @Test
    fun `empty arguments are an empty input`() {
        val toolCalls = """{"tool_calls":[{"id":"t1","type":"function","function":{"name":"get","arguments":""}}]}"""

        assertEquals(
            """[{"role":"assistant","content":[{"type":"tool_use","id":"t1","name":"get","input":{}}]}]""",
            messages(message("assistant", null, toolCalls)),
        )
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = ["[1]", "1", "not json", "\"x\""])
    fun `arguments that are not a JSON object are refused`(arguments: String) {
        val escaped = arguments.replace("\"", "\\\"")
        val toolCalls =
            """{"tool_calls":[{"id":"t","type":"function","function":{"name":"g","arguments":"$escaped"}}]}"""

        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].tool_calls[0].function.arguments"),
            refused(listOf(message("assistant", null, toolCalls))),
        )
    }

    @Test
    fun `a tool call with another member or another type is refused`() {
        val extra = """{"tool_calls":[{"id":"t","x":1,"function":{"name":"g","arguments":""}}]}"""
        val type = """{"tool_calls":[{"id":"t","type":"custom","function":{"name":"g","arguments":""}}]}"""
        val noId = """{"tool_calls":[{"type":"function","function":{"name":"g","arguments":""}}]}"""

        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", "messages[0].tool_calls[0].x"),
            refused(listOf(message("assistant", null, extra))),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_value", "messages[0].tool_calls[0].type"),
            refused(listOf(message("assistant", null, type))),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].tool_calls[0]"),
            refused(listOf(message("assistant", null, noId))),
        )
    }

    @Test
    fun `thinking blocks go first, verbatim, before text and tool_use`() {
        val extras =
            """{"llmgw":{"thinking_blocks":[{"type":"thinking","thinking":"","signature":"s"},
            {"type":"redacted_thinking","data":"r"}]},
            "tool_calls":[{"id":"t","type":"function","function":{"name":"g","arguments":"{}"}}]}"""

        assertEquals(
            """[{"role":"assistant","content":[{"type":"thinking","thinking":"","signature":"s"},""" +
                """{"type":"redacted_thinking","data":"r"},{"type":"text","text":"ok"},""" +
                """{"type":"tool_use","id":"t","name":"g","input":{}}]}]""",
            messages(message("assistant", MessageContent.Text("ok"), extras)),
        )
    }

    @Test
    fun `members outside a thinking block's allow-list are dropped before the upstream write`() {
        val extras =
            """{"llmgw":{"thinking_blocks":[
            {"type":"thinking","thinking":"t","signature":"s","data":"d","cache_control":{"type":"ephemeral"}},
            {"type":"redacted_thinking","data":"r","signature":"s","thinking":"t","x":1}]}}"""

        assertEquals(
            """[{"role":"assistant","content":[{"type":"thinking","thinking":"t","signature":"s"},""" +
                """{"type":"redacted_thinking","data":"r"},{"type":"text","text":"ok"}]}]""",
            messages(message("assistant", MessageContent.Text("ok"), extras)),
        )
    }

    @Test
    fun `llmgw of another shape, or holding a block that is not thinking, is refused`() {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].llmgw"),
            refused(listOf(message("assistant", null, """{"llmgw":{"other":1}}"""))),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].llmgw.thinking_blocks[0]"),
            refused(listOf(message("assistant", null, """{"llmgw":{"thinking_blocks":[{"type":"tool_use"}]}}"""))),
        )
    }

    @Test
    fun `thinking_blocks set to null counts as absent`() {
        val plain = """[{"role":"assistant","content":"ok"}]"""

        assertEquals(
            plain,
            messages(message("assistant", MessageContent.Text("ok"), """{"llmgw":{"thinking_blocks":null}}""")),
        )
        assertEquals(plain, messages(message("assistant", MessageContent.Text("ok"), """{"llmgw":{}}""")))
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].llmgw"),
            refused(listOf(message("assistant", null, """{"llmgw":{"thinking_blocks":null,"other":1}}"""))),
        )
    }

    @Test
    fun `a tool message becomes a tool_result in a user message`() {
        assertEquals(
            """[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"42"}]}]""",
            messages(message("tool", MessageContent.Text("42"), """{"tool_call_id":"t"}""")),
        )
    }

    @Test
    fun `a tool message of text parts keeps them, a non-text part is refused, no id is refused`() {
        val text = message("tool", MessageContent.Parts(listOf(ContentPart.Text("a"))), """{"tool_call_id":"t"}""")
        val image =
            message(
                "tool",
                MessageContent.Parts(listOf(ContentPart.Opaque(obj("{}")))),
                """{"tool_call_id":"t"}""",
            )

        assertEquals(
            """[{"role":"user","content":[{"type":"tool_result","tool_use_id":"t",""" +
                """"content":[{"type":"text","text":"a"}]}]}]""",
            messages(text),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_parameter", "messages[0].content[0]"),
            refused(listOf(image)),
        )
        assertEquals(
            ProviderFailure.UnsupportedByProvider("invalid_request", "messages[0].tool_call_id"),
            refused(listOf(message("tool", MessageContent.Text("x")))),
        )
    }

    @Test
    fun `two tool messages then a user message are one user message, tool_result blocks first`() {
        val json =
            messages(
                message("tool", MessageContent.Text("1"), """{"tool_call_id":"a"}"""),
                message("tool", MessageContent.Text("2"), """{"tool_call_id":"b"}"""),
                user("more"),
            )

        assertEquals(
            """[{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"1"},""" +
                """{"type":"tool_result","tool_use_id":"b","content":"2"},{"type":"text","text":"more"}]}]""",
            json,
        )
    }

    @Test
    fun `a tool run before an assistant message stands alone`() {
        val json =
            messages(
                message("tool", MessageContent.Text("1"), """{"tool_call_id":"a"}"""),
                message("assistant", MessageContent.Text("ok")),
            )

        assertEquals(
            """[{"role":"user","content":[{"type":"tool_result","tool_use_id":"a","content":"1"}]},""" +
                """{"role":"assistant","content":"ok"}]""",
            json,
        )
    }

    @Test
    fun `a member set to null on a message counts as absent`() {
        assertEquals(
            """[{"role":"assistant","content":"ok"}]""",
            messages(message("assistant", MessageContent.Text("ok"), """{"refusal":null,"tool_calls":null}""")),
        )
    }

    @Test
    fun `a function tool translates, keeping cache_control and strict`() {
        val tools =
            """{"tools":[{"type":"function","cache_control":{"type":"e"},"function":{"name":"g","description":"d",
            "parameters":{"type":"object","properties":{"a":{"type":"string"}}},"strict":true}}]}"""

        assertEquals(
            """[{"name":"g","description":"d",""" +
                """"input_schema":{"type":"object","properties":{"a":{"type":"string"}}},""" +
                """"strict":true,"cache_control":{"type":"e"}}]""",
            mapper.writeValueAsString(body(extras = tools).get("tools")),
        )
    }

    @Test
    fun `a tool with no parameters gets an empty object schema`() {
        val tools = """{"tools":[{"type":"function","function":{"name":"g"}}]}"""

        assertEquals(
            """[{"name":"g","input_schema":{"type":"object","properties":{}}}]""",
            mapper.writeValueAsString(body(extras = tools).get("tools")),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"tools":[{"type":"web_search"}]} | tools[0]""",
            """{"tools":[{"type":"custom","custom":{"name":"x"}}]} | tools[0]""",
            """{"tools":[{"type":"function","function":{"name":"g","extra":1}}]} | tools[0].function.extra""",
            """{"tools":[{"type":"function","function":{"name":"g"},"other":1}]} | tools[0].other""",
        ],
    )
    fun `another tool type or member is unsupported_parameter`(
        extras: String,
        param: String,
    ) {
        assertEquals(ProviderFailure.UnsupportedByProvider("unsupported_parameter", param), refused(extras = extras))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """"auto" | {"type":"auto"}""",
            """"none" | {"type":"none"}""",
            """"required" | {"type":"any"}""",
            """{"type":"function","function":{"name":"g"}} | {"type":"tool","name":"g"}""",
        ],
    )
    fun `tool_choice translates`(
        choice: String,
        expected: String,
    ) {
        val body = body(extras = """{"tools":[$TOOL],"tool_choice":$choice}""")

        assertEquals(expected, mapper.writeValueAsString(body.get("tool_choice")))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"type":"allowed_tools","allowed_tools":{}}""",
            """{"type":"custom","custom":{"name":"x"}}""",
            """"other"""",
            """{"type":"function","function":{"name":"g","x":1}}""",
        ],
    )
    fun `another tool_choice is unsupported_value`(choice: String) {
        assertEquals(
            ProviderFailure.UnsupportedByProvider("unsupported_value", "tool_choice"),
            refused(extras = """{"tools":[$TOOL],"tool_choice":$choice}"""),
        )
    }

    @Test
    fun `parallel_tool_calls false disables parallel use, with or without a choice, but not with none`() {
        val serial = """"parallel_tool_calls":false"""

        assertEquals(
            """{"type":"auto","disable_parallel_tool_use":true}""",
            mapper.writeValueAsString(body(extras = """{"tools":[$TOOL],$serial}""").get("tool_choice")),
        )
        assertEquals(
            """{"type":"any","disable_parallel_tool_use":true}""",
            mapper.writeValueAsString(
                body(extras = """{"tools":[$TOOL],"tool_choice":"required",$serial}""").get("tool_choice"),
            ),
        )
        assertEquals(
            """{"type":"none"}""",
            mapper.writeValueAsString(
                body(extras = """{"tools":[$TOOL],"tool_choice":"none",$serial}""").get("tool_choice"),
            ),
        )
    }

    @Test
    fun `parallel_tool_calls true or no tools adds nothing`() {
        assertNull(body(extras = """{"tools":[$TOOL],"parallel_tool_calls":true}""").get("tool_choice"))
        assertNull(body(extras = """{"parallel_tool_calls":false}""").get("tool_choice"))
    }

    @Test
    fun `parallel_tool_calls of another type is refused, with or without tools`() {
        listOf("\"x\"", "0", "[]", "{}").forEach { value ->
            val refusal = ProviderFailure.UnsupportedByProvider("unsupported_value", "parallel_tool_calls")

            assertEquals(refusal, refused(extras = """{"tools":[$TOOL],"parallel_tool_calls":$value}"""))
            assertEquals(refusal, refused(extras = """{"parallel_tool_calls":$value}"""))
        }
    }

    @Test
    fun `parallel_tool_calls set to null counts as absent`() {
        assertNull(body(extras = """{"tools":[$TOOL],"parallel_tool_calls":null}""").get("tool_choice"))
    }

    @Test
    fun `the outcome does not depend on member order`() {
        val one = """{"temperature":1,"tools":[$TOOL],"tool_choice":"required","stop":"x"}"""
        val two = """{"stop":"x","tool_choice":"required","tools":[$TOOL],"temperature":1}"""

        assertEquals(json(extras = one), json(extras = two))
    }

    private companion object {
        const val TOOL = """{"type":"function","function":{"name":"g"}}"""
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val DEPLOYMENT = Deployment(DeploymentId("anthropic"), ProviderId("anthropic"), ModelName("provider-model"))
    }
}
