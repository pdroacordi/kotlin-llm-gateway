package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScriptedFinish
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ThinkingSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ToolCallSpec
import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import io.github.pdroacordi.llmgateway.mockprovider.web.member
import io.github.pdroacordi.llmgateway.mockprovider.web.optInt
import io.github.pdroacordi.llmgateway.mockprovider.web.optString
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** The scripted members of a scenario's `content`, with the bounds of GW-03 section 7. */
internal class ScriptedContentJson(
    private val mapper: JsonMapper,
) {
    fun toolCalls(
        node: JsonNode,
        path: String,
    ): List<ToolCallSpec> {
        if (!node.isArray || node.size() > MAX_TOOL_CALLS) {
            reject(path, "'$path' must be an array of at most $MAX_TOOL_CALLS tool calls.")
        }
        return node.values().mapIndexed { index, call -> toolCall(call, "$path[$index]") }
    }

    private fun toolCall(
        node: JsonNode,
        path: String,
    ): ToolCallSpec {
        obj(node, path, TOOL_CALL_MEMBERS, "$path.")
        val id = node.optString("id", "$path.id")
        if (id != null && !TOOL_ID.matches(id)) reject("$path.id", "'$path.id' must match ${TOOL_ID.pattern}.")
        val name = node.optString("name", "$path.name") ?: reject("$path.name", "'$path.name' is required.")
        if (!TOOL_NAME.matches(name)) reject("$path.name", "'$path.name' must match ${TOOL_NAME.pattern}.")
        val input = node.member("input")?.takeIf { it.isObject }
        if (input == null) reject("$path.input", "'$path.input' is required and must be a JSON object.")
        return ToolCallSpec(
            id = id,
            name = name,
            inputJson = mapper.writeValueAsString(input),
            chunkChars = node.optInt("chunkChars", CHUNK_CHARS, "$path.chunkChars"),
        )
    }

    fun finish(
        node: JsonNode,
        path: String,
    ): ScriptedFinish? =
        node.optString("finish", path)?.let { wire ->
            ScriptedFinish.fromWire(wire)
                ?: reject(path, "'$path' must be one of ${ScriptedFinish.entries.joinToString { it.wire }}.")
        }

    fun thinking(
        node: JsonNode,
        path: String,
    ): List<ThinkingSpec> {
        if (!node.isArray || node.size() > MAX_THINKING) {
            reject(path, "'$path' must be an array of at most $MAX_THINKING blocks.")
        }
        return node.values().mapIndexed { index, block ->
            val item = "$path[$index]"
            obj(block, item, setOf("signature", "redacted"), "$item.")
            val signature = block.optString("signature", "$item.signature")
            val redacted = block.optString("redacted", "$item.redacted")
            if ((signature == null) == (redacted == null) || (signature ?: redacted).isNullOrEmpty()) {
                reject(item, "'$item' must set exactly one of signature and redacted, not empty.")
            }
            ThinkingSpec(signature, redacted)
        }
    }

    private companion object {
        val TOOL_CALL_MEMBERS = setOf("id", "name", "input", "chunkChars")
        const val MAX_TOOL_CALLS = 64
        const val MAX_THINKING = 16
        val CHUNK_CHARS = 1..4096
        val TOOL_ID = Regex("""[a-zA-Z0-9_-]{1,64}""")
        val TOOL_NAME = Regex("""[a-zA-Z0-9_-]{1,128}""")
    }
}
