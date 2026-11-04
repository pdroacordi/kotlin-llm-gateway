package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter

class LeakyHandler(
    val adapter: OpenAiChatAdapter,
    val config: io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig,
)
