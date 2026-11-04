package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** [system] is null when no text was hoisted. */
internal class WrittenConversation(
    val system: JsonNode?,
    val messages: ArrayNode,
)

/**
 * `messages` to Anthropic's `system` and `messages`, in the order of GW-03 section 4, Messages (ADR-GW-03-015). One
 * instance per request: `i` and `j` in a refused path index the client's arrays.
 */
internal class AnthropicConversationWriter(
    private val nodes: JsonNodeFactory,
    trees: ObjectReader,
    private val refusals: Refusals,
) {
    private val texts = TextBlockWriter(nodes, refusals)
    private val assistantBlocks = AssistantBlockWriter(nodes, trees, refusals)
    private val system = mutableListOf<ObjectNode>()
    private var plainSystem: String? = null
    private val messages = nodes.arrayNode()

    // tool_result blocks waiting for the user message that may follow them.
    private val results = mutableListOf<ObjectNode>()

    fun write(conversation: List<ChatMessage>): WrittenConversation {
        conversation.forEachIndexed(::message)
        flushResults()
        if (messages.isEmpty) refusals.invalidRequest("messages")
        return WrittenConversation(systemNode(), messages)
    }

    private fun systemNode(): JsonNode? =
        when {
            system.isEmpty() -> null
            system.size == 1 && plainSystem != null -> nodes.stringNode(plainSystem)
            else -> nodes.arrayNode(system.size).also { array -> system.forEach(array::add) }
        }

    private fun message(
        index: Int,
        message: ChatMessage,
    ) {
        val path = "messages[$index]"
        when (message.role.value) {
            "system", "developer" -> hoist(message, path)
            "user" -> user(message, path)
            "assistant" -> assistant(message, path)
            "tool" -> tool(message, path)
            else -> refusals.unsupportedValue("$path.role")
        }
    }

    private fun hoist(
        message: ChatMessage,
        path: String,
    ) {
        checkMembers(message, path, NAME_ONLY)
        val content = message.content
        if (content is MessageContent.Text && content.text.isNotEmpty()) plainSystem = content.text
        system += texts.nonEmpty(content, path)
    }

    private fun user(
        message: ChatMessage,
        path: String,
    ) {
        checkMembers(message, path, NAME_ONLY)
        val node = nodes.objectNode().put("role", "user")
        val content = message.content
        if (results.isEmpty() && content !is MessageContent.Parts) {
            node.put("content", (content as? MessageContent.Text)?.text.orEmpty())
        } else {
            // A following text must not land ahead of a result (ADR-GW-03-015).
            val blocks = node.putArray("content")
            results.forEach(blocks::add)
            results.clear()
            texts.nonEmpty(content, path).forEach(blocks::add)
        }
        messages.add(node)
    }

    private fun assistant(
        message: ChatMessage,
        path: String,
    ) {
        flushResults()
        checkMembers(message, path, ASSISTANT_MEMBERS)
        val extras = message.extras.present()
        val thinking = assistantBlocks.thinking(extras[ChatExtensions.MEMBER], path)
        val toolUses = assistantBlocks.toolUses(extras["tool_calls"], path)
        val node = nodes.objectNode().put("role", "assistant")
        val content = message.content
        if (thinking.isEmpty() && toolUses.isEmpty() && content !is MessageContent.Parts) {
            node.put("content", (content as? MessageContent.Text)?.text.orEmpty())
        } else {
            val blocks = node.putArray("content")
            thinking.forEach(blocks::add)
            texts.nonEmpty(content, path).forEach(blocks::add)
            toolUses.forEach(blocks::add)
        }
        messages.add(node)
    }

    private fun tool(
        message: ChatMessage,
        path: String,
    ) {
        checkMembers(message, path, TOOL_MEMBERS)
        val id = message.extras.present()["tool_call_id"] as? JsonValue.Text
        if (id == null) refusals.invalidRequest("$path.tool_call_id")
        val block = nodes.objectNode().put("type", "tool_result").put("tool_use_id", id?.value.orEmpty())
        when (val content = message.content) {
            null -> {
                block.put("content", "")
            }

            is MessageContent.Text -> {
                block.put("content", content.text)
            }

            is MessageContent.Parts -> {
                val parts = block.putArray("content")
                texts.blocks(content, path).forEach(parts::add)
            }
        }
        results += block
    }

    private fun flushResults() {
        if (results.isEmpty()) return
        val node = nodes.objectNode().put("role", "user")
        val blocks = node.putArray("content")
        results.forEach(blocks::add)
        results.clear()
        messages.add(node)
    }

    private fun checkMembers(
        message: ChatMessage,
        path: String,
        allowed: Set<String>,
    ) {
        message.extras
            .present()
            .keys
            .firstOrNull { it !in allowed }
            ?.let { refusals.unsupportedParameter("$path.$it") }
    }

    private companion object {
        val NAME_ONLY = setOf("name")
        val ASSISTANT_MEMBERS = setOf("name", "tool_calls", ChatExtensions.MEMBER)
        val TOOL_MEMBERS = setOf("name", "tool_call_id")
    }
}
