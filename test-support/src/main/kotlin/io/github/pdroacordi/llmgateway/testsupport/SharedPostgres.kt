package io.github.pdroacordi.llmgateway.testsupport

import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration

/**
 * One Postgres container per test JVM, started on first access and removed by Ryuk when the JVM ends; never stop it
 * (ADR-FND-02-007). Database, user and password are `llmgw`. State is shared: create a schema or a database per test
 * class.
 */
object SharedPostgres {
    private const val START_TIMEOUT_SECONDS = 60L
    private const val NAME = "llmgw"

    /** The started container; the first access starts it, concurrent first accesses wait for the same one. */
    val container: PostgreSQLContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PostgreSQLContainer(StackImages.POSTGRES)
            .withDatabaseName(NAME)
            .withUsername(NAME)
            .withPassword(NAME)
            .withStartupTimeout(Duration.ofSeconds(START_TIMEOUT_SECONDS))
            .also { it.start() }
    }
}
