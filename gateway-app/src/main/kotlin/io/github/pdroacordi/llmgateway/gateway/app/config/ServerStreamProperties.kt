package io.github.pdroacordi.llmgateway.gateway.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `llmgw.server.*`. [clientWriteTimeout]: how long a client's socket may take none of the bytes pending for it. */
@ConfigurationProperties("llmgw.server")
data class ServerStreamProperties(
    val clientWriteTimeout: Duration,
) {
    /**
     * Below [lowestIdleTimeout], the lowest of the enabled providers, so a stalled client is seen as a client, not as a
     * silent provider (ADR-GW-02-009). With none enabled, startup stops on that instead.
     */
    fun validate(lowestIdleTimeout: Duration?) {
        check(clientWriteTimeout.isPositive) { "$PREFIX.client-write-timeout must be positive." }
        check(lowestIdleTimeout == null || clientWriteTimeout < lowestIdleTimeout) {
            "$PREFIX.client-write-timeout must be below the lowest stream.idle-timeout of the enabled providers: " +
                "llmgw.providers.openai.stream.idle-timeout, llmgw.providers.anthropic.stream.idle-timeout."
        }
    }

    private companion object {
        const val PREFIX = "llmgw.server"
    }
}
