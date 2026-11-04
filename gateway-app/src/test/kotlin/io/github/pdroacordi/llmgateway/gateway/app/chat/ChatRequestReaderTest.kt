package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.ContentPart
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class ChatRequestReaderTest {
    private fun read(json: String): ChatRequest = ChatRequestReader.read(TREES.readTree(json) as ObjectNode)

    @Test
    fun `the minimal body is read`() {
        val request = read("""{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}""")

        assertEquals(ModelName("mock-chat"), request.model)
        val message = request.messages.single()
        assertEquals(Role("user"), message.role)
        assertEquals(MessageContent.Text("Hi"), message.content)
        assertEquals(JsonValue.Object.EMPTY, request.extras)
    }

    @Test
    fun `every other top-level member goes to extras, in order, stream excluded`() {
        val request =
            read(
                """{"x_future":{"a":[1,null]},"model":"m","seed":9007199254740993,"stream":false,""" +
                    """"messages":[{"role":"user"}],"temperature":0.7,"user":null}""",
            )

        assertEquals(
            listOf("x_future", "seed", "temperature", "user"),
            request.extras.members.keys
                .toList(),
        )
        assertEquals(JsonValue.Number("9007199254740993"), request.extras.members["seed"])
        assertEquals(JsonValue.Number("0.7"), request.extras.members["temperature"])
        assertEquals(JsonValue.Null, request.extras.members["user"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["false", "null"])
    fun `stream false or null is accepted`(stream: String) {
        read("""{"model":"m","messages":[{"role":"user"}],"stream":$stream}""")
    }

    @Test
    fun `any role passes`() {
        val request = read("""{"model":"m","messages":[{"role":"a-role-from-2030"}]}""")

        assertEquals(Role("a-role-from-2030"), request.messages.single().role)
    }

    @ParameterizedTest
    @ValueSource(strings = ["""{"role":"user","content":null}""", """{"role":"user"}"""])
    fun `content may be null or absent`(message: String) {
        assertNull(read("""{"model":"m","messages":[$message]}""").messages.single().content)
    }

    // Spelling changes, the value does not, except the sign of zero (GW-01 section 4, "Narrower than OpenAI").
    @Test
    fun `a number keeps its value, not its spelling`() {
        val extras =
            read("""{"model":"m","messages":[{"role":"user"}],"a":1e5,"b":-0.0,"c":1e-5,"d":1.10}""").extras.members

        assertEquals(JsonValue.Number("1E+5"), extras["a"])
        assertEquals(JsonValue.Number("0.0"), extras["b"])
        assertEquals(JsonValue.Number("0.00001"), extras["c"])
        assertEquals(JsonValue.Number("1.10"), extras["d"])
    }

    @Test
    fun `absent and null content read the same, so both go upstream as a null member`() {
        val absent = read("""{"model":"m","messages":[{"role":"tool","tool_call_id":"c1"}]}""").messages.single()
        val present =
            read("""{"model":"m","messages":[{"role":"tool","content":null,"tool_call_id":"c1"}]}""").messages.single()

        assertEquals(present, absent)
    }

    @Test
    fun `message members beside role and content go to the message extras`() {
        val message =
            read(
                """{"model":"m","messages":[{"role":"assistant","content":null,"name":"bot",""" +
                    """"tool_calls":[{"id":"c1"}]}]}""",
            ).messages.single()

        assertEquals(
            listOf("name", "tool_calls"),
            message.extras.members.keys
                .toList(),
        )
    }

    @Test
    fun `a text part keeps its extras and anything else stays opaque`() {
        val content =
            read(
                """{"model":"m","messages":[{"role":"user","content":[""" +
                    """{"type":"text","text":"Hi","hint":true},""" +
                    """{"type":"image_url","image_url":{"url":"data:x"}},""" +
                    """{"type":"text","text":5},""" +
                    """7]}]}""",
            ).messages.single().content as MessageContent.Parts

        assertEquals(
            ContentPart.Text("Hi", JsonValue.Object(mapOf("hint" to JsonValue.Bool(true)))),
            content.parts[0],
        )
        assertEquals(
            ContentPart.Opaque(
                JsonValue.Object(
                    linkedMapOf(
                        "type" to JsonValue.Text("image_url"),
                        "image_url" to JsonValue.Object(mapOf("url" to JsonValue.Text("data:x"))),
                    ),
                ),
            ),
            content.parts[1],
        )
        assertEquals(
            ContentPart.Opaque(
                JsonValue.Object(linkedMapOf("type" to JsonValue.Text("text"), "text" to JsonValue.Number("5"))),
            ),
            content.parts[2],
        )
        assertEquals(ContentPart.Opaque(JsonValue.Number("7")), content.parts[3])
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    fun `each validation row answers with its error`(
        body: String,
        expected: GatewayError.InvalidRequest,
    ) {
        assertEquals(expected, assertThrows<GatewayErrorException> { read(body) }.error)
    }

    companion object {
        private val TREES = JsonMapper.builder().build().exactTreeReader()
        private const val USER = """{"role":"user","content":"Hi"}"""

        private fun case(
            body: String,
            expected: GatewayError.InvalidRequest,
        ) = Arguments.of(body, expected)

        @JvmStatic
        fun rejected(): List<Arguments> {
            val errors = GatewayError.InvalidRequest
            return listOf(
                case("""{"messages":[$USER]}""", errors.modelRequired),
                case("""{"model":5,"messages":[$USER]}""", errors.modelRequired),
                case("""{"model":null,"messages":[$USER]}""", errors.modelRequired),
                case("""{"model":"  ","messages":[$USER]}""", errors.modelRequired),
                case("""{"model":"m"}""", errors.messagesRequired),
                case("""{"model":"m","messages":"Hi"}""", errors.messagesRequired),
                case("""{"model":"m","messages":null}""", errors.messagesRequired),
                case("""{"model":"m","messages":[]}""", errors.messagesRequired),
                case("""{"model":"m","messages":[$USER,5]}""", errors.messageNotObject(1)),
                case("""{"model":"m","messages":[{"content":"Hi"}]}""", errors.roleRequired(0)),
                case("""{"model":"m","messages":[$USER,{"role":5}]}""", errors.roleRequired(1)),
                case("""{"model":"m","messages":[{"role":" "}]}""", errors.roleRequired(0)),
                case("""{"model":"m","messages":[{"role":"user","content":5}]}""", errors.contentMistyped(0)),
                case("""{"model":"m","messages":[{"role":"user","content":{}}]}""", errors.contentMistyped(0)),
                case("""{"model":"m","messages":[$USER],"stream":"yes"}""", errors.streamMistyped),
                case("""{"model":"m","messages":[$USER],"stream":1}""", errors.streamMistyped),
                case("""{"model":"m","messages":[$USER],"stream":true}""", errors.streamingUnavailable),
                // The first row that fails answers, across messages too (GW-01 section 4).
                case("""{"model":5,"messages":[]}""", errors.modelRequired),
                case("""{"model":"m","messages":[{},5]}""", errors.messageNotObject(1)),
                case("""{"model":"m","messages":[{"role":"u","content":5},{}]}""", errors.roleRequired(1)),
                case(
                    """{"model":"m","messages":[{"role":"u","content":5}],"stream":true}""",
                    errors.contentMistyped(0),
                ),
            )
        }
    }
}
