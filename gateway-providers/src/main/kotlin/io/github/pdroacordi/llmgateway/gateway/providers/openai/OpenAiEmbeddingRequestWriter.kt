package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** [EmbeddingCall] to the request body. An absent `encoding_format` stays absent (ADR-GW-05-004). */
internal object OpenAiEmbeddingRequestWriter {
    fun write(
        call: EmbeddingCall,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val body = nodes.objectNode()
        body.put("model", call.deployment.model.value)
        body.set("input", input(call.input, nodes))
        // The gateway's own member: OpenAI does not know it (ADR-020).
        val extras = call.extras.members - ChatExtensions.MEMBER
        extras.forEach { (name, value) -> body.set(name, value.toJsonNode(nodes)) }
        return body
    }

    private fun input(
        input: EmbeddingInput,
        nodes: JsonNodeFactory,
    ): JsonNode =
        when (input) {
            is EmbeddingInput.Text -> {
                nodes.stringNode(input.text)
            }

            is EmbeddingInput.Texts -> {
                nodes.arrayNode(input.texts.size).also { array -> input.texts.forEach { array.add(it) } }
            }

            is EmbeddingInput.TokenIds -> {
                tokenIds(input.ids, nodes)
            }

            is EmbeddingInput.TokenIdLists -> {
                nodes.arrayNode(input.lists.size).also { array ->
                    input.lists.forEach { array.add(tokenIds(it, nodes)) }
                }
            }
        }

    private fun tokenIds(
        ids: LongArray,
        nodes: JsonNodeFactory,
    ): ArrayNode = nodes.arrayNode(ids.size).also { array -> ids.forEach { array.add(it) } }
}
