package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiChatAdapter

typealias Adapter = OpenAiChatAdapter

typealias QualifiedConfig = io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig

private typealias LocalAdapter = OpenAiChatAdapter

class Wiring(
    val adapter: LocalAdapter,
)
