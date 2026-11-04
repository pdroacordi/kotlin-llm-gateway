package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** `tools`, `tool_choice` and `parallel_tool_calls` to their Messages form (GW-03 section 4, Tools). */
internal class AnthropicToolWriter(
    private val nodes: JsonNodeFactory,
    private val refusals: Refusals,
) {
    fun write(
        extras: Map<String, JsonValue>,
        body: ObjectNode,
    ) {
        val tools = extras["tools"]
        when {
            tools == null -> Unit
            tools is JsonValue.Array -> body.set("tools", tools(tools))
            else -> refusals.unsupportedValue("tools")
        }
        val parallel = extras["parallel_tool_calls"]
        if (parallel != null && parallel !is JsonValue.Bool) refusals.unsupportedValue("parallel_tool_calls")
        val serial = parallel == JsonValue.Bool(false) && tools is JsonValue.Array
        toolChoice(extras["tool_choice"], serial)?.let { body.set("tool_choice", it) }
    }

    private fun tools(tools: JsonValue.Array): ArrayNode {
        val array = nodes.arrayNode(tools.items.size)
        tools.items.forEachIndexed { index, tool -> tool(tool, "tools[$index]")?.let(array::add) }
        return array
    }

    private fun tool(
        tool: JsonValue,
        path: String,
    ): ObjectNode? {
        val members = (tool as? JsonValue.Object)?.present()
        val function = (members?.get("function") as? JsonValue.Object)?.present()
        if (members?.get("type") != FUNCTION || function == null) {
            refusals.unsupportedParameter(path)
            return null
        }
        members.keys.firstOrNull { it !in TOOL_MEMBERS }?.let { refusals.unsupportedParameter("$path.$it") }
        function.keys.firstOrNull { it !in FUNCTION_MEMBERS }?.let {
            refusals.unsupportedParameter(
                "$path.function.$it",
            )
        }
        return translated(function, members["cache_control"])
    }

    private fun translated(
        function: Map<String, JsonValue>,
        cacheControl: JsonValue?,
    ): ObjectNode {
        val node = nodes.objectNode()
        function["name"]?.let { node.set("name", it.toJsonNode(nodes)) }
        function["description"]?.let { node.set("description", it.toJsonNode(nodes)) }
        val schema = function["parameters"]?.toJsonNode(nodes)
        node.set("input_schema", schema ?: nodes.objectNode().put("type", "object").also { it.putObject("properties") })
        function["strict"]?.let { node.set("strict", it.toJsonNode(nodes)) }
        cacheControl?.let { node.set("cache_control", it.toJsonNode(nodes)) }
        return node
    }

    private fun toolChoice(
        choice: JsonValue?,
        serial: Boolean,
    ): ObjectNode? {
        val type = choiceType(choice)
        val node =
            when {
                choice == null && serial -> nodes.objectNode().put("type", "auto")
                choice == null -> null
                type == null -> named(choice)
                else -> nodes.objectNode().put("type", type)
            }
        if (serial && type != NONE) node?.put("disable_parallel_tool_use", true)
        return node
    }

    private fun choiceType(choice: JsonValue?): String? =
        when ((choice as? JsonValue.Text)?.value) {
            "auto" -> "auto"
            NONE -> NONE
            "required" -> "any"
            else -> null
        }

    private fun named(choice: JsonValue): ObjectNode? {
        val members = (choice as? JsonValue.Object)?.present()
        val function = (members?.get("function") as? JsonValue.Object)?.present()
        val name = function?.get("name") as? JsonValue.Text
        val exact = members?.keys == CHOICE_MEMBERS && function?.keys == setOf("name")
        if (members?.get("type") != FUNCTION || name == null || !exact) {
            refusals.unsupportedValue("tool_choice")
            return null
        }
        return nodes.objectNode().put("type", "tool").put("name", name.value)
    }

    private companion object {
        const val NONE = "none"
        val FUNCTION = JsonValue.Text("function")
        val TOOL_MEMBERS = setOf("type", "function", "cache_control")
        val FUNCTION_MEMBERS = setOf("name", "description", "parameters", "strict")
        val CHOICE_MEMBERS = setOf("type", "function")
    }
}
