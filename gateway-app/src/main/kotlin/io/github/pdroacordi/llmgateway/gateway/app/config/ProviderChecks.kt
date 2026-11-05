package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamConfig
import org.springframework.util.unit.DataSize
import java.net.URI
import java.net.URISyntaxException
import java.time.Duration

data class PoolProperties(
    val maxConnections: Int,
    val pendingAcquireTimeout: Duration,
    val maxIdleTime: Duration,
    val maxLifeTime: Duration,
)

data class StreamProperties(
    val firstByteTimeout: Duration,
    val idleTimeout: Duration,
    val totalTimeout: Duration,
    val maxEventSize: DataSize,
)

/**
 * The bounds every provider's properties share, naming properties only (ADR-GW-01-012). [prefix] is the provider's
 * property prefix, [keyVariable] the environment variable its key comes from.
 */
internal class ProviderChecks(
    private val prefix: String,
    private val keyVariable: String,
) {
    fun key(apiKey: ApiKey) {
        check(!apiKey.isBlank) { "$prefix.api-key is blank: set the $keyVariable environment variable." }
        // Otherwise Netty refuses the header on every call, with health UP (ADR-GW-01-012).
        check(apiKey.isVisibleAscii) {
            "$prefix.api-key has a character outside visible ASCII, such as a trailing newline: " +
                "fix the $keyVariable environment variable."
        }
    }

    fun positive(
        name: String,
        value: Duration,
    ) {
        check(value.isPositive) { "$prefix.$name must be positive." }
    }

    /** Bytes. */
    fun maxResponseSize(
        name: String,
        size: DataSize,
    ): Int {
        check(size in MIN_RESPONSE_SIZE..MAX_RESPONSE_SIZE) { "$prefix.$name must be 1KB to 256MB." }
        return size.toBytes().toInt()
    }

    fun pool(pool: PoolProperties): PoolConfig {
        positive("pool.pending-acquire-timeout", pool.pendingAcquireTimeout)
        positive("pool.max-idle-time", pool.maxIdleTime)
        positive("pool.max-life-time", pool.maxLifeTime)
        check(pool.maxConnections > 0) { "$prefix.pool.max-connections must be positive." }
        return PoolConfig(pool.maxConnections, pool.pendingAcquireTimeout, pool.maxIdleTime, pool.maxLifeTime)
    }

    fun stream(stream: StreamProperties): StreamConfig {
        positive("stream.first-byte-timeout", stream.firstByteTimeout)
        positive("stream.idle-timeout", stream.idleTimeout)
        positive("stream.total-timeout", stream.totalTimeout)
        check(stream.firstByteTimeout <= stream.totalTimeout) {
            "$prefix.stream.first-byte-timeout must not exceed stream.total-timeout."
        }
        check(stream.idleTimeout <= stream.totalTimeout) {
            "$prefix.stream.idle-timeout must not exceed stream.total-timeout."
        }
        check(stream.maxEventSize in MIN_EVENT_SIZE..MAX_EVENT_SIZE) {
            "$prefix.stream.max-event-size must be 1KB to 16MB."
        }
        return StreamConfig(
            firstByteTimeout = stream.firstByteTimeout,
            idleTimeout = stream.idleTimeout,
            totalTimeout = stream.totalTimeout,
            maxEventBytes = stream.maxEventSize.toBytes().toInt(),
        )
    }

    // The value is not echoed: user info is exactly what this refuses.
    fun baseUrl(baseUrl: String): URI {
        val url =
            try {
                URI(baseUrl)
            } catch (_: URISyntaxException) {
                null
            }
        val valid = url != null && url.scheme in SCHEMES && url.host != null && url.userInfo == null
        check(valid) { "$prefix.base-url must be an http or https URL with a host and no user info." }
        return checkNotNull(url)
    }

    private companion object {
        val SCHEMES = setOf("http", "https")
        val MIN_RESPONSE_SIZE: DataSize = DataSize.ofKilobytes(1)
        val MAX_RESPONSE_SIZE: DataSize = DataSize.ofMegabytes(256)
        val MIN_EVENT_SIZE: DataSize = DataSize.ofKilobytes(1)
        val MAX_EVENT_SIZE: DataSize = DataSize.ofMegabytes(16)
    }
}
