package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** `response_format` to `output_config.format` (GW-06 section 4, ADR-GW-06-002). */
internal class AnthropicResponseFormatWriter(
    private val nodes: JsonNodeFactory,
    private val refusals: Refusals,
) {
    fun write(
        extras: Map<String, JsonValue>,
        body: ObjectNode,
    ) {
        val format = extras[MEMBER] ?: return
        val members = (format as? JsonValue.Object)?.present()
        when {
            members == null -> refusals.unsupportedValue(MEMBER)
            members["type"] == TEXT -> refuseOthers(members, setOf("type"), MEMBER)
            members["type"] == JSON_SCHEMA -> schema(members)?.let { outputConfig(it, body) }
            else -> refusals.unsupportedValue("$MEMBER.type")
        }
    }

    private fun schema(members: Map<String, JsonValue>): JsonValue.Object? {
        refuseOthers(members, setOf("type", SCHEMA_HOLDER), MEMBER)
        val holder = (members[SCHEMA_HOLDER] as? JsonValue.Object)?.present()
        if (holder == null) {
            refusals.invalidRequest("$MEMBER.$SCHEMA_HOLDER")
            return null
        }
        refuseOthers(holder, HOLDER_MEMBERS, "$MEMBER.$SCHEMA_HOLDER")
        val schema = holder["schema"] as? JsonValue.Object
        if (schema == null) refusals.invalidRequest("$MEMBER.$SCHEMA_HOLDER.schema")
        return schema
    }

    private fun refuseOthers(
        members: Map<String, JsonValue>,
        known: Set<String>,
        path: String,
    ) {
        members.keys.firstOrNull { it !in known }?.let { refusals.unsupportedParameter("$path.$it") }
    }

    private fun outputConfig(
        schema: JsonValue.Object,
        body: ObjectNode,
    ) {
        val format = body.putObject("output_config").putObject("format")
        format.put("type", "json_schema")
        format.set("schema", schema.toJsonNode(nodes))
    }

    private companion object {
        const val MEMBER = "response_format"
        const val SCHEMA_HOLDER = "json_schema"
        val TEXT = JsonValue.Text("text")
        val JSON_SCHEMA = JsonValue.Text(SCHEMA_HOLDER)

        // `name`, `description` and `strict` have no Messages member, so they are dropped (ADR-GW-06-002).
        val HOLDER_MEMBERS = setOf("schema", "name", "description", "strict")
    }
}
