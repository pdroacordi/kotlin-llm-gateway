package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiEmbeddingAdapterConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * `llmgw.providers.openai.embeddings.*`. [maxConnections] caps upstream reads; [maxInFlightBytes] caps the request and
 * reply memory of the process, a quarter of the heap when unset (ADR-GW-05-011).
 */
data class EmbeddingProperties(
    val requestTimeout: Duration,
    val maxResponseSize: DataSize,
    val maxConnections: Int,
    val maxInFlightBytes: DataSize?,
    val defaultDimensions: Int,
    val cpuThreads: Int,
    val cpuQueueSize: Int,
    val busyRetryAfter: Duration,
) {
    /** Bytes. [heapBytes] is `Runtime.maxMemory()`. */
    fun inFlightBytes(heapBytes: Long): Long = maxInFlightBytes?.toBytes() ?: (heapBytes / UNSET_HEAP_DIVISOR)

    /** A heap too small for one full reply still starts: calls priced above the budget are refused, not an OOM. */
    fun heapWarning(heapBytes: Long): String? {
        val budget = inFlightBytes(heapBytes)
        return if (budget >= maxResponseSize.toBytes()) {
            null
        } else {
            "embeddings.max-in-flight-bytes is unset, so $budget bytes, a quarter of the heap: below " +
                "embeddings.max-response-size, so a call priced above it is always refused (ADR-GW-05-011)."
        }
    }

    internal companion object {
        const val UNSET_HEAP_DIVISOR = 4
        const val SET_HEAP_DIVISOR = 2
    }
}

/**
 * `llmgw.providers.openai.*`. No `init` checks: a constructor failure goes through Boot's binder, which could report
 * the key beside it, so [toAdapterConfig] validates and names properties only (ADR-GW-01-012).
 */
@ConfigurationProperties("llmgw.providers.openai")
data class OpenAiProperties(
    val baseUrl: String,
    val apiKey: ApiKey,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseSize: DataSize,
    val pool: PoolProperties,
    val stream: StreamProperties,
    val embeddings: EmbeddingProperties,
    val enabled: Boolean = true,
) {
    fun toAdapterConfig(): OpenAiAdapterConfig {
        val checks = ProviderChecks(PREFIX, "OPENAI_API_KEY")
        checks.key(apiKey)
        checks.positive("connect-timeout", connectTimeout)
        checks.positive("request-timeout", requestTimeout)
        return OpenAiAdapterConfig(
            baseUrl = checks.baseUrl(baseUrl),
            apiKey = apiKey,
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            maxResponseBytes = checks.maxResponseSize("max-response-size", maxResponseSize),
            pool = checks.pool(pool),
            stream = checks.stream(stream),
        )
    }

    /** `base-url`, `api-key`, `connect-timeout` and the other `pool.*` values are shared with chat. */
    fun toEmbeddingAdapterConfig(heapBytes: Long = Runtime.getRuntime().maxMemory()): OpenAiEmbeddingAdapterConfig {
        val checks = ProviderChecks(PREFIX, "OPENAI_API_KEY")
        checks.key(apiKey)
        checks.positive("connect-timeout", connectTimeout)
        checks.positive("embeddings.request-timeout", embeddings.requestTimeout)
        checks.positive("embeddings.busy-retry-after", embeddings.busyRetryAfter)
        check(embeddings.maxConnections > 0) { "$PREFIX.embeddings.max-connections must be positive." }
        check(embeddings.cpuThreads > 0) { "$PREFIX.embeddings.cpu-threads must be positive." }
        check(embeddings.cpuQueueSize > 0) { "$PREFIX.embeddings.cpu-queue-size must be positive." }
        check(embeddings.defaultDimensions > 0) { "$PREFIX.embeddings.default-dimensions must be positive." }
        embeddings.maxInFlightBytes?.let { checkBudget(it, heapBytes) }
        return OpenAiEmbeddingAdapterConfig(
            baseUrl = checks.baseUrl(baseUrl),
            apiKey = apiKey,
            connectTimeout = connectTimeout,
            requestTimeout = embeddings.requestTimeout,
            maxResponseBytes = checks.maxResponseSize("embeddings.max-response-size", embeddings.maxResponseSize),
            pool = checks.pool(pool).copy(maxConnections = embeddings.maxConnections),
            cpuThreads = embeddings.cpuThreads,
            cpuQueueSize = embeddings.cpuQueueSize,
        )
    }

    private fun checkBudget(
        budget: DataSize,
        heapBytes: Long,
    ) {
        // A reservation is capped at one reply, so this is what lets the largest call run alone (ADR-GW-05-011).
        check(budget >= embeddings.maxResponseSize) {
            "$PREFIX.embeddings.max-in-flight-bytes must be at least embeddings.max-response-size."
        }
        val most = heapBytes / EmbeddingProperties.SET_HEAP_DIVISOR
        check(budget.toBytes() <= most) {
            "$PREFIX.embeddings.max-in-flight-bytes must be at most half the heap, $most bytes: " +
                "heap holds about twice the budget (ADR-GW-05-011)."
        }
    }

    private companion object {
        const val PREFIX = "llmgw.providers.openai"
    }
}
