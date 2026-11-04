package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.AssistantMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory

class ChatResponseWriterTest {
    private val mapper = JsonMapper.builder().build()

    private fun written(
        completion: ChatCompletion,
        usage: Usage,
    ): String = mapper.writeValueAsString(ChatResponseWriter.write(completion, usage, JsonNodeFactory.instance))

    @Test
    fun `the section 4 shape comes out member for member, in the provider's order`() {
        val message =
            AssistantMessage(
                "Hi there",
                JsonValue.Object(
                    linkedMapOf("refusal" to JsonValue.Null, "annotations" to JsonValue.Array(emptyList())),
                ),
            )
        val choice =
            ChatChoice(0, message, FinishReason("stop"), JsonValue.Object(mapOf("logprobs" to JsonValue.Null)))
        val completion =
            ChatCompletion(
                "chatcmpl-mock-000000000001",
                1793750400,
                ModelName("mock-chat"),
                listOf(choice),
                JsonValue.Object(
                    linkedMapOf(
                        "service_tier" to JsonValue.Text("default"),
                        "system_fingerprint" to JsonValue.Text("fp_mock"),
                    ),
                ),
            )
        val usage =
            Usage(
                TokenCount(14),
                TokenCount(2),
                TokenCount(0),
                extras =
                    JsonValue.Object(
                        linkedMapOf(
                            "prompt_tokens_details" to
                                JsonValue.Object(mapOf("cached_tokens" to JsonValue.Number("0"))),
                            "completion_tokens_details" to
                                JsonValue.Object(mapOf("reasoning_tokens" to JsonValue.Number("0"))),
                        ),
                    ),
            )

        assertEquals(
            """{"id":"chatcmpl-mock-000000000001","object":"chat.completion","created":1793750400,""" +
                """"model":"mock-chat","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there",""" +
                """"refusal":null,"annotations":[]},"logprobs":null,"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,""" +
                """"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}},""" +
                """"service_tier":"default","system_fingerprint":"fp_mock"}""",
            written(completion, usage),
        )
    }

    @Test
    fun `an estimate is written like any usage, with no extra member`() {
        val completion =
            ChatCompletion("id", 1, ModelName("m"), listOf(ChatChoice(0, AssistantMessage("Hi"), FinishReason("stop"))))

        assertEquals(
            """{"id":"id","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,""" +
                """"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":6,"completion_tokens":1,"total_tokens":7}}""",
            written(completion, Usage(TokenCount(6), TokenCount(1), estimated = true)),
        )
    }

    @Test
    fun `null content and a null finish reason stay null members`() {
        val completion =
            ChatCompletion("id", 1, ModelName("m"), listOf(ChatChoice(1, AssistantMessage(null), null)))

        assertEquals(
            """{"id":"id","object":"chat.completion","created":1,"model":"m","choices":[{"index":1,""" +
                """"message":{"role":"assistant","content":null},"finish_reason":null}],""" +
                """"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""",
            written(completion, Usage(TokenCount(0), TokenCount(0))),
        )
    }
}
