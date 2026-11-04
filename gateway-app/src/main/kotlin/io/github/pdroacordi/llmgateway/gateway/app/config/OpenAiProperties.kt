package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.openai.OpenAiAdapterConfig
import io.github.pdroacordi.llmgateway.gateway.providers.openai.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.openai.StreamConfig
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.net.URI
import java.net.URISyntaxException
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
    val pool: Pool,
    val stream: Stream,
) {
    data class Pool(
        val maxConnections: Int,
        val pendingAcquireTimeout: Duration,
        val maxIdleTime: Duration,
        val maxLifeTime: Duration,
    )

    data class Stream(
        val firstByteTimeout: Duration,
        val idleTimeout: Duration,
        val totalTimeout: Duration,
        val maxEventSize: DataSize,
    )

    fun toAdapterConfig(): OpenAiAdapterConfig {
        check(!apiKey.isBlank) { "$PREFIX.api-key is blank: set the OPENAI_API_KEY environment variable." }
        // Otherwise Netty refuses the header on every call, with health UP (ADR-GW-01-012).
        check(apiKey.isVisibleAscii) {
            "$PREFIX.api-key has a character outside visible ASCII, such as a trailing newline: " +
                "fix the OPENAI_API_KEY environment variable."
        }
        positive("connect-timeout", connectTimeout)
        positive("request-timeout", requestTimeout)
        positive("pool.pending-acquire-timeout", pool.pendingAcquireTimeout)
        positive("pool.max-idle-time", pool.maxIdleTime)
        positive("pool.max-life-time", pool.maxLifeTime)
        check(
            maxResponseSize in MIN_RESPONSE_SIZE..MAX_RESPONSE_SIZE,
        ) { "$PREFIX.max-response-size must be 1KB to 256MB." }
        check(pool.maxConnections > 0) { "$PREFIX.pool.max-connections must be positive." }
        checkStream()
        return OpenAiAdapterConfig(
            baseUrl = validBaseUrl(),
            apiKey = apiKey,
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            maxResponseBytes = maxResponseSize.toBytes().toInt(),
            pool =
                PoolConfig(
                    maxConnections = pool.maxConnections,
                    pendingAcquireTimeout = pool.pendingAcquireTimeout,
                    maxIdleTime = pool.maxIdleTime,
                    maxLifeTime = pool.maxLifeTime,
                ),
            stream =
                StreamConfig(
                    firstByteTimeout = stream.firstByteTimeout,
                    idleTimeout = stream.idleTimeout,
                    totalTimeout = stream.totalTimeout,
                    maxEventBytes = stream.maxEventSize.toBytes().toInt(),
                ),
        )
    }

    private fun checkStream() {
        positive("stream.first-byte-timeout", stream.firstByteTimeout)
        positive("stream.idle-timeout", stream.idleTimeout)
        positive("stream.total-timeout", stream.totalTimeout)
        check(stream.firstByteTimeout <= stream.totalTimeout) {
            "$PREFIX.stream.first-byte-timeout must not exceed stream.total-timeout."
        }
        check(stream.idleTimeout <= stream.totalTimeout) {
            "$PREFIX.stream.idle-timeout must not exceed stream.total-timeout."
        }
        check(stream.maxEventSize in MIN_EVENT_SIZE..MAX_EVENT_SIZE) {
            "$PREFIX.stream.max-event-size must be 1KB to 16MB."
        }
    }

    // The value is not echoed: user info is exactly what this refuses.
    private fun validBaseUrl(): URI {
        val url =
            try {
                URI(baseUrl)
            } catch (_: URISyntaxException) {
                null
            }
        val valid = url != null && url.scheme in SCHEMES && url.host != null && url.userInfo == null
        check(valid) { "$PREFIX.base-url must be an http or https URL with a host and no user info." }
        return checkNotNull(url)
    }

    private fun positive(
        name: String,
        value: Duration,
    ) {
        check(value.isPositive) { "$PREFIX.$name must be positive." }
    }

    private companion object {
        const val PREFIX = "llmgw.providers.openai"
        val SCHEMES = setOf("http", "https")
        val MIN_RESPONSE_SIZE: DataSize = DataSize.ofKilobytes(1)
        val MAX_RESPONSE_SIZE: DataSize = DataSize.ofMegabytes(256)
        val MIN_EVENT_SIZE: DataSize = DataSize.ofKilobytes(1)
        val MAX_EVENT_SIZE: DataSize = DataSize.ofMegabytes(16)
    }
}
