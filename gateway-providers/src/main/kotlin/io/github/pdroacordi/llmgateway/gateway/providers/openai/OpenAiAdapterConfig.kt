package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamConfig
import java.net.URI
import java.time.Duration

/** [requestTimeout] bounds a whole non-stream call; [maxResponseBytes] limits one non-stream or error response. */
data class OpenAiAdapterConfig(
    val baseUrl: URI,
    val apiKey: ApiKey,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseBytes: Int,
    val pool: PoolConfig,
    val stream: StreamConfig,
)
