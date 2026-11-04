package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamConfig
import java.net.URI
import java.time.Duration

/**
 * [version] is the `anthropic-version` header; [defaultMaxTokens] is sent when a client names no cap, which the API
 * requires (ADR-GW-03-006). [requestTimeout] bounds a whole non-stream call; [maxResponseBytes] one non-stream or error
 * response.
 */
data class AnthropicAdapterConfig(
    val baseUrl: URI,
    val apiKey: ApiKey,
    val version: String,
    val defaultMaxTokens: Int,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseBytes: Int,
    val pool: PoolConfig,
    val stream: StreamConfig,
)
