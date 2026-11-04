package io.github.pdroacordi.llmgateway.gateway.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `llmgw.server.*`. [clientWriteTimeout]: how long a client's socket may take none of the bytes pending for it. */
@ConfigurationProperties("llmgw.server")
data class ServerStreamProperties(
    val clientWriteTimeout: Duration,
) {
    /** Below [idleTimeout], so a stalled client is seen as a client, not as a silent provider (ADR-GW-02-009). */
    fun validate(idleTimeout: Duration) {
        check(clientWriteTimeout.isPositive) { "$PREFIX.client-write-timeout must be positive." }
        check(clientWriteTimeout < idleTimeout) {
            "$PREFIX.client-write-timeout must be below llmgw.providers.openai.stream.idle-timeout."
        }
    }

    private companion object {
        const val PREFIX = "llmgw.server"
    }
}
