package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import java.net.URI
import java.time.Duration

/**
 * [requestTimeout] bounds the exchange, and each stage on the lane apart from it; [maxResponseBytes] limits one reply;
 * [pool] caps upstream reads, not memory (ADR-GW-05-011); [cpuThreads] sizes the lane.
 */
data class OpenAiEmbeddingAdapterConfig(
    val baseUrl: URI,
    val apiKey: ApiKey,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseBytes: Int,
    val pool: PoolConfig,
    val cpuThreads: Int,
    val cpuQueueSize: Int,
)
