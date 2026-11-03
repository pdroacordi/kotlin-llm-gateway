package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import io.github.pdroacordi.llmgateway.mockprovider.generation.ContentPart
import io.github.pdroacordi.llmgateway.mockprovider.generation.Conversation
import io.github.pdroacordi.llmgateway.mockprovider.generation.Turn
import io.github.pdroacordi.llmgateway.mockprovider.web.member
import io.github.pdroacordi.llmgateway.mockprovider.web.optBoolean
import io.github.pdroacordi.llmgateway.mockprovider.web.optInt
import io.github.pdroacordi.llmgateway.mockprovider.web.requireNonEmptyArray
import io.github.pdroacordi.llmgateway.mockprovider.web.requireString
import tools.jackson.databind.JsonNode

/** [cap] is `max_tokens`, required by the Messages API. */
data class MessagesRequest(
    val model: String,
    val conversation: Conversation,
    val stream: Boolean,
    val cap: Int,
)

/**
 * Reads the members of a Messages request the mock uses: `system` becomes a first turn with role `system`; `text`
 * blocks and the text inside `tool_result` blocks count; other blocks are ignored (FND-03 section 5, Generation).
 */
object AnthropicRequests {
    fun messages(body: JsonNode): MessagesRequest {
        if (!body.isObject) reject(null, "The request body must be a JSON object.")
        val model = body.requireString("model")
        val cap = body.optInt("max_tokens", 0..Int.MAX_VALUE) ?: reject("max_tokens", "'max_tokens' is required.")
        val messages = body.requireNonEmptyArray("messages")
        val system =
            body.member("system")?.let { node ->
                when {
                    node.isString -> Turn("system", node.stringValue())
                    node.isArray -> Turn.anthropic("system", blocks(node))
                    else -> reject("system", "'system' must be a string or an array of text blocks.")
                }
            }
        val turns = messages.mapIndexed(::turn)
        return MessagesRequest(
            model = model,
            conversation = Conversation(model, listOfNotNull(system) + turns),
            stream = body.optBoolean("stream") ?: false,
            cap = cap,
        )
    }

    private fun turn(
        index: Int,
        message: JsonNode,
    ): Turn {
        val param = "messages[$index]"
        if (!message.isObject) reject(param, "'$param' must be a JSON object.")
        val role = message.requireString("role", "$param.role")
        val content = message.member("content")
        return when {
            content == null -> Turn(role, "")
            content.isString -> Turn(role, content.stringValue())
            content.isArray -> Turn.anthropic(role, blocks(content))
            else -> reject("$param.content", "'$param.content' must be a string or an array of blocks.")
        }
    }

    private fun blocks(array: JsonNode): List<ContentPart> = array.values().map(::block)

    private fun block(node: JsonNode): ContentPart {
        val text = node.member("text")
        val content = node.member("content")
        return when (node.member("type")?.takeIf { it.isString }?.stringValue()) {
            "text" -> if (text != null && text.isString) ContentPart.Text(text.stringValue()) else ContentPart.Other
            "tool_result" -> ContentPart.ToolResult(toolResult(content))
            else -> ContentPart.Other
        }
    }

    private fun toolResult(content: JsonNode?): List<ContentPart> =
        when {
            content == null -> emptyList()
            content.isString -> listOf(ContentPart.Text(content.stringValue()))
            content.isArray -> blocks(content)
            else -> emptyList()
        }
}
