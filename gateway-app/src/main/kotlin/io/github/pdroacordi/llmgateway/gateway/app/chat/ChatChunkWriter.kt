package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChunkChoice
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** [ChatChunk] to OpenAI's chunk: typed members first, then the provider's others in the order received. */
object ChatChunkWriter {
    /** The one place this name lives, pending the user's confirmation of it (ADR-GW-02-005). */
    const val EXTENSION_MEMBER = "x_gateway"
    const val USAGE_ESTIMATED_MEMBER = "usage_estimated"
    private const val OBJECT = "chat.completion.chunk"

    /** With [includeUsage], every chunk has `usage`, `null` but on the one that reports it; otherwise none has it. */
    fun write(
        chunk: ChatChunk,
        includeUsage: Boolean,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val body = nodes.objectNode()
        body.put("id", chunk.id)
        body.put("object", OBJECT)
        body.put("created", chunk.created)
        body.put("model", chunk.model.value)
        val choices = body.putArray("choices")
        chunk.choices.forEach { choices.add(choice(it, nodes)) }
        if (includeUsage) {
            body.set(
                "usage",
                chunk.usage?.let { ChatResponseWriter.usage(it, nodes) } ?: nodes.nullNode(),
            )
        }
        body.withExtras(chunk.extras, nodes)
        if (includeUsage && chunk.usage?.estimated == true) {
            body.putObject(EXTENSION_MEMBER).put(USAGE_ESTIMATED_MEMBER, true)
        }
        return body
    }

    // A null content or finish_reason sits in extras, so a member the provider left out stays out.
    private fun choice(
        choice: ChunkChoice,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val delta = nodes.objectNode()
        choice.delta.content?.let { delta.put("content", it) }
        delta.withExtras(choice.delta.extras, nodes)

        val node = nodes.objectNode()
        node.put("index", choice.index)
        node.set("delta", delta)
        node.withExtras(choice.extras, nodes)
        choice.finishReason?.let { node.put("finish_reason", it.value) }
        return node
    }
}
