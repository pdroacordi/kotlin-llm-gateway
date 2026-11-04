package io.github.pdroacordi.llmgateway.gateway.providers.openai

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
import io.github.pdroacordi.llmgateway.gateway.core.chat.StreamOptions
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import kotlin.uuid.Uuid

class OpenAiRequestWriterTest {
    private val mapper = JsonMapper.builder().build()

    private fun written(
        message: ChatMessage,
        extras: JsonValue.Object = JsonValue.Object.EMPTY,
    ): String {
        val request = ChatRequest(ModelName("client-model"), listOf(message), extras)
        val call = ChatCall(REQUEST_ID, DEPLOYMENT, request)
        return mapper.writeValueAsString(OpenAiRequestWriter.write(call, JsonNodeFactory.instance))
    }

    @Test
    fun `model comes from the deployment, not from the request`() {
        val json = written(ChatMessage(Role("user"), MessageContent.Text("Hi")))

        assertEquals("""{"model":"provider-model","messages":[{"role":"user","content":"Hi"}]}""", json)
    }

    @Test
    fun `top-level extras follow the typed members unchanged`() {
        val extras =
            JsonValue.Object(
                linkedMapOf(
                    "x_future" to
                        JsonValue.Object(
                            mapOf("a" to JsonValue.Array(listOf(JsonValue.Number("1"), JsonValue.Null))),
                        ),
                    "seed" to JsonValue.Number("9007199254740993"),
                    "temperature" to JsonValue.Number("0.7"),
                ),
            )

        assertEquals(
            """{"model":"provider-model","messages":[{"role":"user","content":null}],""" +
                """"x_future":{"a":[1,null]},"seed":9007199254740993,"temperature":0.7}""",
            written(ChatMessage(Role("user"), null), extras),
        )
    }

    @Test
    fun `message extras stay on the message`() {
        val toolCalls = JsonValue.Array(listOf(JsonValue.Object(mapOf("id" to JsonValue.Text("c1")))))
        val message =
            ChatMessage(
                Role("assistant"),
                null,
                JsonValue.Object(mapOf("name" to JsonValue.Text("bot"), "tool_calls" to toolCalls)),
            )

        assertEquals(
            """{"model":"provider-model","messages":[{"role":"assistant","content":null,""" +
                """"name":"bot","tool_calls":[{"id":"c1"}]}]}""",
            written(message),
        )
    }

    @Test
    fun `parts keep their order, a text part its extras, and an opaque part everything`() {
        val image =
            JsonValue.Object(
                linkedMapOf(
                    "type" to JsonValue.Text("image_url"),
                    "image_url" to JsonValue.Object(mapOf("url" to JsonValue.Text("data:x"))),
                ),
            )
        val parts =
            MessageContent.Parts(
                listOf(
                    ContentPart.Text("Hi", JsonValue.Object(mapOf("cache_hint" to JsonValue.Bool(true)))),
                    ContentPart.Opaque(image),
                    ContentPart.Opaque(JsonValue.Number("5")),
                ),
            )

        assertEquals(
            """{"model":"provider-model","messages":[{"role":"user","content":[""" +
                """{"type":"text","text":"Hi","cache_hint":true},""" +
                """{"type":"image_url","image_url":{"url":"data:x"}},5]}]}""",
            written(ChatMessage(Role("user"), parts)),
        )
    }

    private fun streamed(options: StreamOptions?): String {
        val request =
            ChatRequest(
                ModelName("client-model"),
                listOf(ChatMessage(Role("user"), MessageContent.Text("Hi"))),
                JsonValue.Object(mapOf("seed" to JsonValue.Number("7"))),
                options,
            )
        val call = ChatCall(REQUEST_ID, DEPLOYMENT, request)
        return mapper.writeValueAsString(OpenAiRequestWriter.writeStream(call, JsonNodeFactory.instance))
    }

    @Test
    fun `writeStream adds stream true and forces include_usage when the client sent no options`() {
        assertEquals(
            """{"model":"provider-model","messages":[{"role":"user","content":"Hi"}],"seed":7,""" +
                """"stream":true,"stream_options":{"include_usage":true}}""",
            streamed(null),
        )
    }

    @Test
    fun `writeStream forces include_usage and keeps the client's other members`() {
        val extras = JsonValue.Object(mapOf("include_obfuscation" to JsonValue.Bool(false)))

        assertEquals(
            """{"model":"provider-model","messages":[{"role":"user","content":"Hi"}],"seed":7,""" +
                """"stream":true,"stream_options":{"include_usage":true,"include_obfuscation":false}}""",
            streamed(StreamOptions(includeUsage = false, extras = extras)),
        )
    }

    @Test
    fun `write stays as it was, with no stream member`() {
        assertEquals(
            """{"model":"provider-model","messages":[{"role":"user","content":"Hi"}]}""",
            written(ChatMessage(Role("user"), MessageContent.Text("Hi"))),
        )
    }

    private companion object {
        val REQUEST_ID = RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057"))
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("provider-model"))
    }
}
