package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.providers.anthropic.AnthropicChatAdapter
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter

class LeakyHandler(
    val adapter: OpenAiChatAdapter,
    val anthropic: AnthropicChatAdapter,
    val config: io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig,
)
