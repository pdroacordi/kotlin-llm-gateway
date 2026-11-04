package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** [ChatCompletion] to the OpenAI body, in the provider's member order (GW-01 section 4). */
object ChatResponseWriter {
    private const val OBJECT = "chat.completion"
    private const val ROLE = "assistant"

    fun write(
        completion: ChatCompletion,
        usage: Usage,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val body = nodes.objectNode()
        body.put("id", completion.id)
        body.put("object", OBJECT)
        body.put("created", completion.created)
        body.put("model", completion.model.value)
        val choices = body.putArray("choices")
        completion.choices.forEach { choices.add(choice(it, nodes)) }
        body.set("usage", usage(usage, nodes))
        return body.withExtras(completion.extras, nodes)
    }

    // OpenAI writes finish_reason after logprobs, so it follows the extras here.
    private fun choice(
        choice: ChatChoice,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val message = nodes.objectNode()
        message.put("role", ROLE)
        message.put("content", choice.message.content)
        message.withExtras(choice.message.extras, nodes)

        val node = nodes.objectNode()
        node.put("index", choice.index)
        node.set("message", message)
        node.withExtras(choice.extras, nodes)
        node.put("finish_reason", choice.finishReason?.value)
        return node
    }

    fun usage(
        usage: Usage,
        nodes: JsonNodeFactory,
    ): ObjectNode {
        val node = nodes.objectNode()
        node.put("prompt_tokens", usage.inputTokens.value)
        node.put("completion_tokens", usage.outputTokens.value)
        node.put("total_tokens", usage.totalTokens.value)
        return node.withExtras(usage.extras, nodes)
    }
}

internal fun ObjectNode.withExtras(
    extras: JsonValue.Object,
    nodes: JsonNodeFactory,
): ObjectNode {
    extras.members.forEach { (name, value) -> set(name, value.toJsonNode(nodes)) }
    return this
}
