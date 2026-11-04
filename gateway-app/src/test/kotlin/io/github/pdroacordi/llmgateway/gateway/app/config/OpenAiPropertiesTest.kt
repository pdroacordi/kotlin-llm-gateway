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
                OpenAiProperties.Pool(
                    maxConnections = 500,
                    pendingAcquireTimeout = Duration.ofSeconds(5),
                    maxIdleTime = Duration.ofSeconds(30),
                    maxLifeTime = Duration.ofMinutes(5),
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
                ),
            ),
        )

        val bound =
            Binder(ConfigurationPropertySources.from(environment.propertySources))
                .bindOrCreate("llmgw.providers.openai", OpenAiProperties::class.java)

        assertEquals("sk-canary-key", bound.apiKey.reveal())
        assertEquals(DataSize.ofMegabytes(16), bound.maxResponseSize)
        assertEquals(500, bound.pool.maxConnections)
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
}
