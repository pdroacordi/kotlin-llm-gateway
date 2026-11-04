package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter

class AllowedWiring(
    val adapter: OpenAiChatAdapter,
)
