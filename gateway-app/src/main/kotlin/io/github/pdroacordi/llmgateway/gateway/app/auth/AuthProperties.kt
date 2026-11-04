package io.github.pdroacordi.llmgateway.gateway.app.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `llmgw.auth.*`. No `init` checks: a constructor failure goes through Boot's binder, which could report a value, so
 * [validate] runs in the bean factory and names properties only (ADR-GW-04-008).
 */
@ConfigurationProperties("llmgw.auth")
data class AuthProperties(
    val hmacSecret: ConfiguredSecret,
    val adminKey: ConfiguredSecret,
    val storeTimeout: Duration,
    val warmupTimeout: Duration,
    val cache: CacheProperties,
) {
    data class CacheProperties(
        val ttl: Duration,
        val maxSize: Long,
        val negativeTtl: Duration,
        val negativeMaxSize: Long,
    )

    fun validate() {
        checkSecret("hmac-secret", "LLMGW_KEY_HMAC_SECRET", hmacSecret)
        checkSecret("admin-key", "LLMGW_ADMIN_KEY", adminKey)
        check(hmacSecret.reveal() != adminKey.reveal()) { "$PREFIX.admin-key must differ from $PREFIX.hmac-secret." }
        checkDuration("store-timeout", storeTimeout, MAX_STORE_TIMEOUT, "10s")
        checkDuration("warmup-timeout", warmupTimeout, MAX_WARMUP_TIMEOUT, "2m")
        checkDuration("cache.ttl", cache.ttl, MAX_TTL, "5m")
        checkDuration("cache.negative-ttl", cache.negativeTtl, MAX_NEGATIVE_TTL, "1m")
        check(cache.maxSize > 0) { "$PREFIX.cache.max-size must be positive." }
        check(cache.negativeMaxSize > 0) { "$PREFIX.cache.negative-max-size must be positive." }
    }

    private fun checkSecret(
        name: String,
        variable: String,
        secret: ConfiguredSecret,
    ) {
        check(secret.length in SECRET_LENGTH && secret.isVisibleAscii) {
            "$PREFIX.$name must be 32 to 512 visible ASCII characters: fix the $variable environment variable."
        }
    }

    private fun checkDuration(
        name: String,
        value: Duration,
        maximum: Duration,
        maximumText: String,
    ) {
        check(value.isPositive && value <= maximum) { "$PREFIX.$name must be positive and at most $maximumText." }
    }

    private companion object {
        const val PREFIX = "llmgw.auth"
        val SECRET_LENGTH = 32..512
        val MAX_STORE_TIMEOUT: Duration = Duration.ofSeconds(10)
        val MAX_WARMUP_TIMEOUT: Duration = Duration.ofMinutes(2)
        val MAX_TTL: Duration = Duration.ofMinutes(5)
        val MAX_NEGATIVE_TTL: Duration = Duration.ofMinutes(1)
    }
}
