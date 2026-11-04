package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.ContentPart
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** Request body to [ChatRequest]. The checks run in the order of GW-01 section 4, and the first failure answers. */
object ChatRequestReader {
    private const val TEXT_PART_TYPE = "text"
    private val REQUEST_MEMBERS = setOf("model", "messages", "stream")
    private val MESSAGE_MEMBERS = setOf("role", "content")
    private val TEXT_PART_MEMBERS = setOf("type", "text")

    fun read(body: ObjectNode): ChatRequest {
        val model = body.get("model").nonBlankText() ?: reject(GatewayError.InvalidRequest.modelRequired)
        val messages =
            body
                .get("messages")
                ?.takeIf { it.isArray && !it.isEmpty }
                ?.values()
                ?.toList()
                ?: reject(GatewayError.InvalidRequest.messagesRequired)
        checkMessages(messages)
        checkStream(body.get("stream"))
        return ChatRequest(ModelName(model), messages.map(::message), body.extras(REQUEST_MEMBERS))
    }

    private fun checkMessages(messages: List<JsonNode>) {
        messages.forEachIndexed { index, message ->
            if (!message.isObject) reject(GatewayError.InvalidRequest.messageNotObject(index))
        }
        messages.forEachIndexed { index, message ->
            message.get("role").nonBlankText() ?: reject(GatewayError.InvalidRequest.roleRequired(index))
        }
        messages.forEachIndexed { index, message ->
            if (!isContent(message.get("content"))) reject(GatewayError.InvalidRequest.contentMistyped(index))
        }
    }

    private fun isContent(node: JsonNode?): Boolean = node == null || node.isNull || node.isString || node.isArray

    private fun checkStream(stream: JsonNode?) {
        if (stream == null || stream.isNull) return
        if (!stream.isBoolean) reject(GatewayError.InvalidRequest.streamMistyped)
        if (stream.booleanValue()) reject(GatewayError.InvalidRequest.streamingUnavailable)
    }

    private fun message(node: JsonNode): ChatMessage =
        ChatMessage(
            role = Role(node.get("role").stringValue()),
            content = content(node.get("content")),
            extras = node.extras(MESSAGE_MEMBERS),
        )

    private fun content(node: JsonNode?): MessageContent? =
        when {
            node == null || node.isNull -> null
            node.isString -> MessageContent.Text(node.stringValue())
            else -> MessageContent.Parts(node.values().map(::part))
        }

    private fun part(node: JsonNode): ContentPart {
        val text = node.get("text")?.takeIf { it.isString }
        val isText = node.isObject && node.get("type")?.takeIf { it.isString }?.stringValue() == TEXT_PART_TYPE
        return if (isText && text != null) {
            ContentPart.Text(text.stringValue(), node.extras(TEXT_PART_MEMBERS))
        } else {
            ContentPart.Opaque(node.toJsonValue())
        }
    }

    private fun JsonNode?.nonBlankText(): String? =
        this?.takeIf { it.isString }?.stringValue()?.takeIf { it.isNotBlank() }

    private fun JsonNode.extras(typed: Set<String>): JsonValue.Object =
        JsonValue.Object(
            properties()
                .filterNot { (name, _) -> name in typed }
                .associate { (name, value) -> name to value.toJsonValue() },
        )

    private fun reject(error: GatewayError.InvalidRequest): Nothing = throw GatewayErrorException(error)
}
