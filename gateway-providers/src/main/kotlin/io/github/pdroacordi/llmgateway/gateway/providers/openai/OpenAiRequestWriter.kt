package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ContentPart
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** [ChatCall] to the request body: typed members first, then extras, so a member's value is never rewritten. */
internal object OpenAiRequestWriter {
    fun write(
        call: ChatCall,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val body = nodes.objectNode()
        body.put("model", call.deployment.model.value)
        val messages = body.putArray("messages")
        call.request.messages.forEach { messages.add(message(it, nodes)) }
        return body.withExtras(call.request.extras.withoutExtension(), nodes)
    }

    /** `include_usage` is always true upstream, whatever the client asked: the gateway needs it (ADR-GW-02-005). */
    fun writeStream(
        call: ChatCall,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val body = write(call, nodes)
        body.put("stream", true)
        val options = body.putObject("stream_options")
        options.put("include_usage", true)
        call.request.streamOptions?.let { options.withExtras(it.extras, nodes) }
        return body
    }

    private fun message(
        message: ChatMessage,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val node = nodes.objectNode()
        node.put("role", message.role.value)
        node.set("content", content(message.content, nodes))
        return node.withExtras(message.extras.withoutExtension(), nodes)
    }

    // The gateway's own member: OpenAI does not know it (ADR-020).
    private fun JsonValue.Object.withoutExtension(): JsonValue.Object =
        JsonValue.Object(members - ChatExtensions.MEMBER)

    private fun content(
        content: MessageContent?,
        nodes: JsonNodeFactory,
    ): JsonNode =
        when (content) {
            null -> {
                nodes.nullNode()
            }

            is MessageContent.Text -> {
                nodes.stringNode(content.text)
            }

            is MessageContent.Parts -> {
                nodes.arrayNode(content.parts.size).also { parts ->
                    content.parts.forEach { parts.add(part(it, nodes)) }
                }
            }
        }

    private fun part(
        part: ContentPart,
        nodes: JsonNodeFactory,
    ): JsonNode =
        when (part) {
            is ContentPart.Text -> {
                val node = nodes.objectNode()
                node.put("type", "text")
                node.put("text", part.text)
                node.withExtras(part.extras, nodes)
            }

            is ContentPart.Opaque -> {
                part.part.toJsonNode(nodes)
            }
        }

    private fun ObjectNode.withExtras(
        extras: JsonValue.Object,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        extras.members.forEach { (name, value) -> set(name, value.toJsonNode(nodes)) }
        return this
    }
}
