package io.github.pdroacordi.llmgateway.gateway.core.chat

import com.anthropic.client.AnthropicClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.client.OpenAIClient
import kotlinx.serialization.json.JsonElement
import tools.jackson.databind.JsonNode

class JacksonInCore(
    val tree: JsonNode,
    val legacy: ObjectMapper,
    val element: JsonElement,
    val openAi: OpenAIClient,
    val anthropic: AnthropicClient,
    val inline: tools.jackson.databind.node.ObjectNode,
)
