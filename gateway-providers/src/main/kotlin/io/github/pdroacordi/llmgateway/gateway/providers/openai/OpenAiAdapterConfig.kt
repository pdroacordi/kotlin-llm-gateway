package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
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

data class PoolConfig(
    val maxConnections: Int,
    val pendingAcquireTimeout: Duration,
    val maxIdleTime: Duration,
    val maxLifeTime: Duration,
)

/** The deadlines of ADR-GW-02-006; [maxEventBytes] bounds one SSE event, not a stream. */
data class StreamConfig(
    val firstByteTimeout: Duration,
    val idleTimeout: Duration,
    val totalTimeout: Duration,
    val maxEventBytes: Int,
)
