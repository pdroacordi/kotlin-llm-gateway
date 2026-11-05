package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.anthropic.AnthropicAdapterConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * `llmgw.providers.anthropic.*`. As with OpenAI, [toAdapterConfig] validates, and runs only for an enabled provider, so
 * a disabled one's values stop nothing (ADR-GW-03-004). [modelPrefixes] go with RTE-01; they are case-sensitive.
 */
@ConfigurationProperties("llmgw.providers.anthropic")
data class AnthropicProperties(
    val baseUrl: String,
    val apiKey: ApiKey,
    val version: String,
    val defaultMaxTokens: Int,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maxResponseSize: DataSize,
    val pool: PoolProperties,
    val stream: StreamProperties,
    val modelPrefixes: List<String> = emptyList(),
    val enabled: Boolean = false,
) {
    fun toAdapterConfig(): AnthropicAdapterConfig {
        val checks = ProviderChecks(PREFIX, "ANTHROPIC_API_KEY")
        checks.key(apiKey)
        checks.positive("connect-timeout", connectTimeout)
        checks.positive("request-timeout", requestTimeout)
        check(VERSION.matches(version)) { "$PREFIX.version must be a date, YYYY-MM-DD." }
        check(defaultMaxTokens > 0) { "$PREFIX.default-max-tokens must be positive." }
        return AnthropicAdapterConfig(
            baseUrl = checks.baseUrl(baseUrl),
            apiKey = apiKey,
            version = version,
            defaultMaxTokens = defaultMaxTokens,
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            maxResponseBytes = checks.maxResponseSize("max-response-size", maxResponseSize),
            pool = checks.pool(pool),
            stream = checks.stream(stream),
        )
    }

    private companion object {
        const val PREFIX = "llmgw.providers.anthropic"
        val VERSION = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
