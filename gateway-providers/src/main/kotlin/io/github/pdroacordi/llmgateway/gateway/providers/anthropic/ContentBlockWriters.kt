package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.ContentPart
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Message content to `text` blocks, keeping a part's `cache_control`; a non-text part is refused. */
internal class TextBlockWriter(
    private val nodes: JsonNodeFactory,
    private val refusals: Refusals,
) {
    fun blocks(
        content: MessageContent?,
        path: String,
    ): List<ObjectNode> =
        when (content) {
            null -> {
                emptyList()
            }

            is MessageContent.Text -> {
                listOf(textBlock(content.text))
            }

            is MessageContent.Parts -> {
                content.parts.mapIndexedNotNull { index, part -> partBlock(part, "$path.content[$index]") }
            }
        }

    /** `text` has minLength 1: an empty block would be the gateway's own 400. */
    fun nonEmpty(
        content: MessageContent?,
        path: String,
    ): List<ObjectNode> = blocks(content, path).filter { it.get("text").stringValue().isNotEmpty() }

    private fun partBlock(
        part: ContentPart,
        path: String,
    ): ObjectNode? =
        when (part) {
            is ContentPart.Opaque -> {
                refusals.unsupportedParameter(path)
                null
            }

            is ContentPart.Text -> {
                val extras = part.extras.present()
                extras.keys.firstOrNull { it != CACHE_CONTROL }?.let { refusals.unsupportedParameter("$path.$it") }
                textBlock(part.text).also { block ->
                    extras[CACHE_CONTROL]?.let { block.set(CACHE_CONTROL, it.toJsonNode(nodes)) }
                }
            }
        }

    private fun textBlock(text: String): ObjectNode = nodes.objectNode().put("type", "text").put("text", text)

    private companion object {
        const val CACHE_CONTROL = "cache_control"
    }
}

/** What only an assistant message holds: echoed thinking blocks and `tool_calls` as `tool_use` blocks. */
internal class AssistantBlockWriter(
    private val nodes: JsonNodeFactory,
    private val trees: ObjectReader,
    private val refusals: Refusals,
) {
    /**
     * A block of any other type is refused: this member must not become a way to write `tool_use` (ADR-GW-03-011).
     * A member outside its type's allow-list is dropped (ADR-GW-03-023).
     */
    fun thinking(
        member: JsonValue?,
        path: String,
    ): List<JsonNode> {
        val own = "$path.${ChatExtensions.MEMBER}"
        val extension = (member as? JsonValue.Object)?.present()
        val blocks = extension?.get(THINKING_BLOCKS) as? JsonValue.Array
        return when {
            // An extension holding only null members holds nothing (GW-03 section 4).
            member == null || extension?.isEmpty() == true -> {
                emptyList()
            }

            blocks == null || extension.size != 1 -> {
                refusals.invalidRequest(own)
                emptyList()
            }

            else -> {
                blocks.items.forEachIndexed { index, block ->
                    if ((block as? JsonValue.Object)?.members?.get("type") !in THINKING_MEMBERS) {
                        refusals.invalidRequest("$own.$THINKING_BLOCKS[$index]")
                    }
                }
                blocks.items.map { allowedMembers(it).toJsonNode(nodes) }
            }
        }
    }

    private fun allowedMembers(block: JsonValue): JsonValue {
        val members = (block as? JsonValue.Object)?.members
        val allowed = THINKING_MEMBERS[members?.get("type")]
        return if (members == null || allowed == null) block else JsonValue.Object(members.filterKeys { it in allowed })
    }

    fun toolUses(
        member: JsonValue?,
        path: String,
    ): List<ObjectNode> =
        when (member) {
            null -> {
                emptyList()
            }

            is JsonValue.Array -> {
                member.items.mapIndexedNotNull { index, call -> toolUse(call, "$path.tool_calls[$index]") }
            }

            else -> {
                refusals.invalidRequest("$path.tool_calls")
                emptyList()
            }
        }

    private fun toolUse(
        call: JsonValue,
        path: String,
    ): ObjectNode? {
        val members = (call as? JsonValue.Object)?.present()
        val function = (members?.get("function") as? JsonValue.Object)?.present()
        val id = members?.get("id") as? JsonValue.Text
        val name = function?.get("name") as? JsonValue.Text
        if (id == null || name == null) {
            refusals.invalidRequest(path)
            return null
        }
        members.keys.firstOrNull { it !in TOOL_CALL_MEMBERS }?.let { refusals.unsupportedParameter("$path.$it") }
        function.keys.firstOrNull { it !in FUNCTION_MEMBERS }?.let {
            refusals.unsupportedParameter("$path.function.$it")
        }
        if (members["type"].let { it != null && it != JsonValue.Text("function") }) {
            refusals.unsupportedValue("$path.type")
        }
        val node =
            nodes
                .objectNode()
                .put("type", "tool_use")
                .put("id", id.value)
                .put("name", name.value)
        node.set("input", input(function["arguments"], "$path.function.arguments"))
        return node
    }

    // Read with the exact reader, so a number keeps its literal.
    private fun input(
        arguments: JsonValue?,
        path: String,
    ): JsonNode {
        val text = (arguments as? JsonValue.Text)?.value
        val parsed =
            when {
                arguments == null || text?.isEmpty() == true -> nodes.objectNode()
                text == null -> null
                else -> trees.readTreeOrNull(text.encodeToByteArray())
            }
        if (parsed?.isObject != true) refusals.invalidRequest(path)
        return parsed ?: nodes.objectNode()
    }

    private companion object {
        const val THINKING_BLOCKS = "thinking_blocks"
        val TOOL_CALL_MEMBERS = setOf("id", "type", "function")
        val FUNCTION_MEMBERS = setOf("name", "arguments")
        val THINKING_MEMBERS: Map<JsonValue, Set<String>> =
            mapOf(
                JsonValue.Text("thinking") to setOf("type", "thinking", "signature"),
                JsonValue.Text("redacted_thinking") to setOf("type", "data"),
            )
    }
}
