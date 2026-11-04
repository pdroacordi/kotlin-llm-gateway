package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

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
            maxResponseBytes = checks.maxResponseSize(maxResponseSize),
            pool = checks.pool(pool),
            stream = checks.stream(stream),
        )
    }

    private companion object {
        const val PREFIX = "llmgw.providers.openai"
    }
}
