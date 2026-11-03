package io.github.pdroacordi.llmgateway.mockprovider.config

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** `mock.*`, checked at startup (FND-03 section 5, Configuration). Settable from the environment (`MOCK_MAX_HOLD`). */
@ConfigurationProperties("mock")
data class MockProperties(
    /** Listed by `/v1/models` only; any model name is accepted. */
    val models: List<String> = listOf("mock-chat", "mock-chat-mini", "mock-claude", "mock-embed"),
    val defaults: Defaults = Defaults(),
    val embeddings: Embeddings = Embeddings(),
    val auth: Auth = Auth(),
    val journal: Journal = Journal(),
    /** The longest a `hang` or `stall` holds a request, and the cap of every scenario latency. */
    val maxHold: Duration = Duration.ofSeconds(DEFAULT_MAX_HOLD_SECONDS),
) {
    init {
        require(!maxHold.isNegative && !maxHold.isZero) { "mock.max-hold must be positive" }
        require(defaults.latency.all { !it.isNegative && it <= maxHold }) {
            "mock.defaults.latency values must be from 0 to mock.max-hold"
        }
        require(embeddings.defaultDimensions in 1..embeddings.maxDimensions) {
            "mock.embeddings.default-dimensions must be from 1 to mock.embeddings.max-dimensions"
        }
    }

    data class Defaults(
        val latency: Latency = Latency(),
        /** Tokens of `lorem` content, 1..4096. */
        val completionTokens: Int = DEFAULT_COMPLETION_TOKENS,
        /** `lorem` or `echo`; `fixed` needs a scenario's text. */
        val contentMode: ContentMode = ContentMode.LOREM,
    ) {
        init {
            require(completionTokens in 1..MAX_COMPLETION_TOKENS) { "mock.defaults.completion-tokens must be 1..4096" }
            require(contentMode != ContentMode.FIXED) { "mock.defaults.content-mode must be lorem or echo" }
        }
    }

    data class Latency(
        val ttft: Duration = Duration.ZERO,
        val perToken: Duration = Duration.ZERO,
        val jitter: Duration = Duration.ZERO,
    ) {
        fun all(check: (Duration) -> Boolean): Boolean = listOf(ttft, perToken, jitter).all(check)
    }

    data class Embeddings(
        val defaultDimensions: Int = DEFAULT_DIMENSIONS,
        val maxDimensions: Int = MAX_DIMENSIONS,
    )

    data class Auth(
        /** `false` accepts requests without a credential. */
        val requireCredential: Boolean = true,
    )

    data class Journal(
        /** Entries kept, 1..100000; older ones are overwritten. */
        val capacity: Int = DEFAULT_CAPACITY,
        /** Bytes of each request body kept. */
        val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
        /** `false` for load runs: bodies are not kept. */
        val captureBodies: Boolean = true,
    ) {
        init {
            require(capacity in 1..MAX_CAPACITY) { "mock.journal.capacity must be 1..100000" }
            require(maxBodyBytes >= 0) { "mock.journal.max-body-bytes must not be negative" }
        }
    }

    private companion object {
        const val DEFAULT_MAX_HOLD_SECONDS = 300L
        const val DEFAULT_COMPLETION_TOKENS = 16
        const val MAX_COMPLETION_TOKENS = 4096
        const val DEFAULT_DIMENSIONS = 1536
        const val MAX_DIMENSIONS = 3072
        const val DEFAULT_CAPACITY = 1000
        const val MAX_CAPACITY = 100_000
        const val DEFAULT_MAX_BODY_BYTES = 16_384
    }
}
