package io.github.pdroacordi.llmgateway.testsupport

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * One Redis container per test JVM, started on first access and removed by Ryuk when the JVM ends; never stop it
 * (ADR-FND-02-007). No auth, no persistence, as in `deploy/compose.yaml`. State is shared: prefix keys per test class,
 * or `FLUSHDB` in `@BeforeEach` (Gradle runs a module's test classes one after another in one JVM).
 */
object SharedRedis {
    private const val PORT = 6379
    private const val START_TIMEOUT_SECONDS = 30L

    /** The started container; the first access starts it, concurrent first accesses wait for the same one. */
    val container: GenericContainer<*> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RedisContainer()
            .withCommand("redis-server", "--save", "", "--appendonly", "no")
            .withExposedPorts(PORT)
            .waitingFor(
                Wait
                    .forLogMessage(".*Ready to accept connections.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(START_TIMEOUT_SECONDS)),
            ).also { it.start() }
    }

    /** `redis://<host>:<mapped port>`. */
    val uri: String
        get() = "redis://${container.host}:${container.getMappedPort(PORT)}"

    // Kotlin cannot infer GenericContainer's self type, so a subclass fixes it.
    private class RedisContainer : GenericContainer<RedisContainer>(StackImages.REDIS)
}
