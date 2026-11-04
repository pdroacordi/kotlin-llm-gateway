package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import java.net.URI
import java.time.Duration

/** [requestTimeout] bounds the whole call; [maxResponseBytes] is the in-memory limit of one response. */
data class OpenAiAdapterConfig(
    val baseUrl: URI,
    val apiKey: ApiKey,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseBytes: Int,
    val pool: PoolConfig,
)

data class PoolConfig(
    val maxConnections: Int,
    val pendingAcquireTimeout: Duration,
    val maxIdleTime: Duration,
    val maxLifeTime: Duration,
)
