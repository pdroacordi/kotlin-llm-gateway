package io.github.pdroacordi.llmgateway.testsupport

import org.testcontainers.redpanda.RedpandaContainer
import java.time.Duration

/**
 * One Redpanda broker per test JVM, started on first access and removed by Ryuk when the JVM ends; never stop it
 * (ADR-FND-02-007). Provisional until ADR-010 is settled. State is shared: use a unique topic name per test.
 */
object SharedRedpanda {
    private const val START_TIMEOUT_SECONDS = 90L

    /** The started container; the first access starts it, concurrent first accesses wait for the same one. */
    val container: RedpandaContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RedpandaContainer(StackImages.REDPANDA)
            .withStartupTimeout(Duration.ofSeconds(START_TIMEOUT_SECONDS))
            .also { it.start() }
    }

    /** Kafka bootstrap address on the mapped port. */
    val bootstrapServers: String
        get() = container.bootstrapServers
}
