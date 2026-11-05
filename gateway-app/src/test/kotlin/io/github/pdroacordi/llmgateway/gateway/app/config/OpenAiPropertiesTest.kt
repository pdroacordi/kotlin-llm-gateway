package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Duration

class OpenAiPropertiesTest {
    private val valid =
        OpenAiProperties(
            baseUrl = "https://api.example.test/v1",
            apiKey = ApiKey("sk-canary-key"),
            connectTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(300),
            maxResponseSize = DataSize.ofMegabytes(16),
            pool =
                PoolProperties(
                    maxConnections = 500,
                    pendingAcquireTimeout = Duration.ofSeconds(5),
                    maxIdleTime = Duration.ofSeconds(30),
                    maxLifeTime = Duration.ofMinutes(5),
                ),
            stream =
                StreamProperties(
                    firstByteTimeout = Duration.ofSeconds(120),
                    idleTimeout = Duration.ofSeconds(60),
                    totalTimeout = Duration.ofMinutes(30),
                    maxEventSize = DataSize.ofMegabytes(1),
                ),
            embeddings =
                EmbeddingProperties(
                    requestTimeout = Duration.ofSeconds(120),
                    maxResponseSize = DataSize.ofMegabytes(256),
                    maxConnections = 64,
                    maxInFlightBytes = null,
                    defaultDimensions = 3072,
                    cpuThreads = 4,
                    cpuQueueSize = 256,
                    busyRetryAfter = Duration.ofSeconds(5),
                ),
        )

    private fun message(properties: OpenAiProperties): String =
        assertThrows<IllegalStateException> { properties.toAdapterConfig() }.message.orEmpty()

    @Test
    fun `Boot binds the key through ApiKey's public String constructor`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf(
                    "llmgw.providers.openai.base-url" to "http://127.0.0.1:8090/v1",
                    "llmgw.providers.openai.api-key" to "sk-canary-key",
                    "llmgw.providers.openai.connect-timeout" to "5s",
                    "llmgw.providers.openai.request-timeout" to "300s",
                    "llmgw.providers.openai.max-response-size" to "16MB",
                    "llmgw.providers.openai.pool.max-connections" to "500",
                    "llmgw.providers.openai.pool.pending-acquire-timeout" to "5s",
                    "llmgw.providers.openai.pool.max-idle-time" to "30s",
                    "llmgw.providers.openai.pool.max-life-time" to "5m",
                    "llmgw.providers.openai.stream.first-byte-timeout" to "120s",
                    "llmgw.providers.openai.stream.idle-timeout" to "60s",
                    "llmgw.providers.openai.stream.total-timeout" to "30m",
                    "llmgw.providers.openai.stream.max-event-size" to "1MB",
                    "llmgw.providers.openai.embeddings.request-timeout" to "120s",
                    "llmgw.providers.openai.embeddings.max-response-size" to "256MB",
                    "llmgw.providers.openai.embeddings.max-connections" to "64",
                    "llmgw.providers.openai.embeddings.max-in-flight-bytes" to "512MB",
                    "llmgw.providers.openai.embeddings.default-dimensions" to "3072",
                    "llmgw.providers.openai.embeddings.cpu-threads" to "4",
                    "llmgw.providers.openai.embeddings.cpu-queue-size" to "256",
                    "llmgw.providers.openai.embeddings.busy-retry-after" to "5s",
                ),
            ),
        )

        val bound =
            Binder(ConfigurationPropertySources.from(environment.propertySources))
                .bindOrCreate("llmgw.providers.openai", OpenAiProperties::class.java)

        assertEquals("sk-canary-key", bound.apiKey.reveal())
        assertEquals(DataSize.ofMegabytes(16), bound.maxResponseSize)
        assertEquals(500, bound.pool.maxConnections)
        assertEquals(Duration.ofMinutes(30), bound.stream.totalTimeout)
        assertEquals(DataSize.ofMegabytes(1), bound.stream.maxEventSize)
        assertEquals(DataSize.ofMegabytes(256), bound.embeddings.maxResponseSize)
    }

    private fun embeddingMessage(properties: OpenAiProperties): String =
        assertThrows<IllegalStateException> { properties.toEmbeddingAdapterConfig(HEAP) }.message.orEmpty()

    @Test
    fun `the embedding values reach the embedding config, with the shared ones and chat's pool timings`() {
        val config = valid.toEmbeddingAdapterConfig(HEAP)

        assertEquals(URI.create("https://api.example.test/v1"), config.baseUrl)
        assertEquals("sk-canary-key", config.apiKey.reveal())
        assertEquals(Duration.ofSeconds(5), config.connectTimeout)
        assertEquals(Duration.ofSeconds(120), config.requestTimeout)
        assertEquals(256 * 1024 * 1024, config.maxResponseBytes)
        assertEquals(64, config.pool.maxConnections)
        assertEquals(Duration.ofSeconds(5), config.pool.pendingAcquireTimeout)
        assertEquals(Duration.ofSeconds(30), config.pool.maxIdleTime)
        assertEquals(Duration.ofMinutes(5), config.pool.maxLifeTime)
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "PT-1S"])
    fun `an embedding request timeout must be positive`(value: String) {
        val embeddings = valid.embeddings.copy(requestTimeout = Duration.parse(value))

        assertEquals(
            "llmgw.providers.openai.embeddings.request-timeout must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @ParameterizedTest
    @ValueSource(longs = [1023, 256 * 1024 * 1024 + 1])
    fun `an embedding reply size outside 1KB to 256MB is refused, naming its own property`(bytes: Long) {
        val embeddings = valid.embeddings.copy(maxResponseSize = DataSize.ofBytes(bytes))

        assertEquals(
            "llmgw.providers.openai.embeddings.max-response-size must be 1KB to 256MB.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `embedding max-connections must be positive`(connections: Int) {
        val embeddings = valid.embeddings.copy(maxConnections = connections)

        assertEquals(
            "llmgw.providers.openai.embeddings.max-connections must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `embedding cpu-threads must be positive`(threads: Int) {
        val embeddings = valid.embeddings.copy(cpuThreads = threads)

        assertEquals(
            "llmgw.providers.openai.embeddings.cpu-threads must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `embedding default-dimensions must be positive`(dimensions: Int) {
        val embeddings = valid.embeddings.copy(defaultDimensions = dimensions)

        assertEquals(
            "llmgw.providers.openai.embeddings.default-dimensions must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "PT-1S"])
    fun `embedding busy-retry-after must be positive`(value: String) {
        val embeddings = valid.embeddings.copy(busyRetryAfter = Duration.parse(value))

        assertEquals(
            "llmgw.providers.openai.embeddings.busy-retry-after must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @Test
    fun `a budget below one reply is refused, since that reply could never run`() {
        val embeddings = valid.embeddings.copy(maxInFlightBytes = DataSize.ofMegabytes(255))

        assertEquals(
            "llmgw.providers.openai.embeddings.max-in-flight-bytes must be at least embeddings.max-response-size.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @Test
    fun `a budget of exactly one reply is valid`() {
        val embeddings = valid.embeddings.copy(maxInFlightBytes = DataSize.ofMegabytes(256))

        assertEquals(4, valid.copy(embeddings = embeddings).toEmbeddingAdapterConfig(HEAP).cpuThreads)
    }

    @Test
    fun `an unset budget is a quarter of the heap`() {
        assertEquals(HEAP / 4, valid.embeddings.inFlightBytes(HEAP))
    }

    @Test
    fun `a set budget is what it says`() {
        val embeddings = valid.embeddings.copy(maxInFlightBytes = DataSize.ofMegabytes(300))

        assertEquals(DataSize.ofMegabytes(300).toBytes(), embeddings.inFlightBytes(HEAP))
    }

    @Test
    fun `a budget of half the heap is valid`() {
        val embeddings = valid.embeddings.copy(maxInFlightBytes = DataSize.ofBytes(HEAP / 2))

        assertEquals(4, valid.copy(embeddings = embeddings).toEmbeddingAdapterConfig(HEAP).cpuThreads)
    }

    @Test
    fun `a budget above half the heap is refused, naming the property`() {
        val embeddings = valid.embeddings.copy(maxInFlightBytes = DataSize.ofBytes(HEAP / 2 + 1))

        assertEquals(
            "llmgw.providers.openai.embeddings.max-in-flight-bytes must be at most half the heap, ${HEAP / 2} bytes: " +
                "heap holds about twice the budget (ADR-GW-05-011).",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @Test
    fun `an unset budget below one reply starts, with a warning that names both properties`() {
        val smallHeap = DataSize.ofMegabytes(512).toBytes()

        assertEquals(4, valid.toEmbeddingAdapterConfig(smallHeap).cpuThreads)
        val warning = checkNotNull(valid.embeddings.heapWarning(smallHeap))
        assertTrue("max-in-flight-bytes" in warning && "max-response-size" in warning) { warning }
    }

    @Test
    fun `a budget that holds a whole reply needs no warning`() {
        assertEquals(null, valid.embeddings.heapWarning(HEAP))
        val set = valid.embeddings.copy(maxInFlightBytes = DataSize.ofMegabytes(256))
        assertEquals(null, set.heapWarning(DataSize.ofMegabytes(512).toBytes()))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `embedding cpu-queue-size must not be below one`(size: Int) {
        val embeddings = valid.embeddings.copy(cpuQueueSize = size)

        assertEquals(
            "llmgw.providers.openai.embeddings.cpu-queue-size must be positive.",
            embeddingMessage(valid.copy(embeddings = embeddings)),
        )
    }

    @Test
    fun `the embedding config checks what it shares with chat`() {
        assertTrue("OPENAI_API_KEY" in embeddingMessage(valid.copy(apiKey = ApiKey(""))))
        assertTrue("base-url" in embeddingMessage(valid.copy(baseUrl = "ftp://host/v1")))
        assertTrue("connect-timeout" in embeddingMessage(valid.copy(connectTimeout = Duration.ZERO)))
    }

    @Test
    fun `the stream values reach the adapter's config`() {
        val stream = valid.toAdapterConfig().stream

        assertEquals(Duration.ofSeconds(120), stream.firstByteTimeout)
        assertEquals(Duration.ofSeconds(60), stream.idleTimeout)
        assertEquals(Duration.ofMinutes(30), stream.totalTimeout)
        assertEquals(1024 * 1024, stream.maxEventBytes)
    }

    @ParameterizedTest
    @ValueSource(strings = ["first-byte-timeout", "idle-timeout", "total-timeout"])
    fun `a stream deadline that is not positive is refused, naming the property`(name: String) {
        val stream =
            when (name) {
                "first-byte-timeout" -> valid.stream.copy(firstByteTimeout = Duration.ZERO)
                "idle-timeout" -> valid.stream.copy(idleTimeout = Duration.ZERO)
                else -> valid.stream.copy(totalTimeout = Duration.ofSeconds(-1))
            }

        assertEquals("llmgw.providers.openai.stream.$name must be positive.", message(valid.copy(stream = stream)))
    }

    @Test
    fun `a first-byte or idle deadline above the total is refused`() {
        val total = Duration.ofSeconds(10)
        val first =
            valid.stream.copy(
                firstByteTimeout = Duration.ofSeconds(11),
                idleTimeout = total,
                totalTimeout = total,
            )
        val idle =
            valid.stream.copy(
                firstByteTimeout = total,
                idleTimeout = Duration.ofSeconds(11),
                totalTimeout = total,
            )

        assertTrue("first-byte-timeout" in message(valid.copy(stream = first)))
        assertTrue("idle-timeout" in message(valid.copy(stream = idle)))
    }

    @ParameterizedTest
    @ValueSource(longs = [1023, 16 * 1024 * 1024 + 1])
    fun `an event size outside 1KB to 16MB is refused`(bytes: Long) {
        val stream = valid.stream.copy(maxEventSize = DataSize.ofBytes(bytes))

        assertEquals(
            "llmgw.providers.openai.stream.max-event-size must be 1KB to 16MB.",
            message(valid.copy(stream = stream)),
        )
    }

    @Test
    fun `a valid set of properties gives the adapter's config`() {
        val config = valid.toAdapterConfig()

        assertEquals(URI.create("https://api.example.test/v1"), config.baseUrl)
        assertEquals("sk-canary-key", config.apiKey.reveal())
        assertEquals(Duration.ofSeconds(5), config.connectTimeout)
        assertEquals(Duration.ofSeconds(300), config.requestTimeout)
        assertEquals(16 * 1024 * 1024, config.maxResponseBytes)
        assertEquals(500, config.pool.maxConnections)
        assertEquals(Duration.ofSeconds(5), config.pool.pendingAcquireTimeout)
        assertEquals(Duration.ofSeconds(30), config.pool.maxIdleTime)
        assertEquals(Duration.ofMinutes(5), config.pool.maxLifeTime)
    }

    @Test
    fun `toString redacts the key`() {
        assertFalse("sk-canary-key" in valid.toString()) { valid.toString() }
        assertTrue("[redacted]" in valid.toString()) { valid.toString() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " "])
    fun `a blank key names the environment variable`(key: String) {
        val message = message(valid.copy(apiKey = ApiKey(key)))

        assertTrue("OPENAI_API_KEY" in message) { message }
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["ftp://host/v1", "//host/v1", "http:///v1", "not a url", "", "http://u:p@host/v1", "file:///x"],
    )
    fun `a base URL must be http or https with a host and no user info`(url: String) {
        val message = message(valid.copy(baseUrl = url))

        assertTrue("llmgw.providers.openai.base-url" in message) { message }
    }

    @ParameterizedTest
    @ValueSource(strings = ["sk-canary-key\n", "sk-canary key", "sk-canary-cl\u00e9"])
    fun `a key with anything outside visible ASCII names the variable, never the value`(key: String) {
        val message = message(valid.copy(apiKey = ApiKey(key)))

        assertTrue("OPENAI_API_KEY" in message) { message }
        assertFalse("canary" in message) { message }
    }

    @Test
    fun `a rejected base URL is not echoed, since it can hold a password`() {
        val message = message(valid.copy(baseUrl = "http://user:canary-password@host/v1"))

        assertFalse("canary-password" in message) { message }
    }

    @Test
    fun `no failure message holds the key`() {
        val message = message(valid.copy(connectTimeout = Duration.ZERO))

        assertFalse("sk-canary-key" in message) { message }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PT0S", "PT-1S"])
    fun `every timeout must be positive`(value: String) {
        val duration = Duration.parse(value)
        val pool = valid.pool

        assertTrue("connect-timeout" in message(valid.copy(connectTimeout = duration)))
        assertTrue("request-timeout" in message(valid.copy(requestTimeout = duration)))
        assertTrue(
            "pending-acquire-timeout" in message(valid.copy(pool = pool.copy(pendingAcquireTimeout = duration))),
        )
        assertTrue("max-idle-time" in message(valid.copy(pool = pool.copy(maxIdleTime = duration))))
        assertTrue("max-life-time" in message(valid.copy(pool = pool.copy(maxLifeTime = duration))))
    }

    @Test
    fun `the response limit is 1 KB to 256 MB`() {
        assertEquals(1024, valid.copy(maxResponseSize = DataSize.ofKilobytes(1)).toAdapterConfig().maxResponseBytes)
        assertEquals(
            256 * 1024 * 1024,
            valid.copy(maxResponseSize = DataSize.ofMegabytes(256)).toAdapterConfig().maxResponseBytes,
        )
        assertTrue("max-response-size" in message(valid.copy(maxResponseSize = DataSize.ofBytes(1023))))
        assertTrue("max-response-size" in message(valid.copy(maxResponseSize = DataSize.ofMegabytes(257))))
    }

    @Test
    fun `the pool needs a connection`() {
        val pool = valid.pool.copy(maxConnections = 0)

        assertTrue("max-connections" in message(valid.copy(pool = pool)))
    }

    private companion object {
        val HEAP: Long = DataSize.ofGigabytes(4).toBytes()
    }
}
