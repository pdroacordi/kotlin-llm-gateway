package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.util.unit.DataSize
import java.time.Duration

class AnthropicPropertiesTest {
    private val valid =
        AnthropicProperties(
            baseUrl = "https://api.example.test/v1",
            apiKey = ApiKey("sk-ant-canary"),
            version = "2023-06-01",
            defaultMaxTokens = 4096,
            connectTimeout = Duration.ofSeconds(5),
            requestTimeout = Duration.ofSeconds(300),
            maxResponseSize = DataSize.ofMegabytes(16),
            pool = PoolProperties(500, Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(5)),
            stream =
                StreamProperties(
                    Duration.ofSeconds(120),
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(30),
                    DataSize.ofMegabytes(1),
                ),
        )

    private fun message(properties: AnthropicProperties): String =
        assertThrows<IllegalStateException> { properties.toAdapterConfig() }.message.orEmpty()

    @Test
    fun `valid values convert`() {
        val config = valid.toAdapterConfig()

        assertEquals("2023-06-01", config.version)
        assertEquals(4096, config.defaultMaxTokens)
        assertEquals(1_048_576, config.stream.maxEventBytes)
        assertEquals(16_777_216, config.maxResponseBytes)
    }

    @Test
    fun `Anthropic is off unless asked`() {
        assertFalse(valid.enabled)
    }

    @Test
    fun `a blank key names the variable`() {
        val message = message(valid.copy(apiKey = ApiKey("")))

        assertTrue("llmgw.providers.anthropic.api-key" in message) { message }
        assertTrue("ANTHROPIC_API_KEY" in message) { message }
    }

    @Test
    fun `a key outside visible ASCII names the variable, not the value`() {
        val message = message(valid.copy(apiKey = ApiKey("sk-ant-canary\n")))

        assertTrue("ANTHROPIC_API_KEY" in message) { message }
        assertFalse("sk-ant-canary" in message) { message }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "latest", "2023-6-1", "2023-06-01x"])
    fun `a version that is not a date is refused`(version: String) {
        assertEquals(
            "llmgw.providers.anthropic.version must be a date, YYYY-MM-DD.",
            message(valid.copy(version = version)),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, -1])
    fun `default-max-tokens must be positive`(tokens: Int) {
        assertEquals(
            "llmgw.providers.anthropic.default-max-tokens must be positive.",
            message(valid.copy(defaultMaxTokens = tokens)),
        )
    }

    @Test
    fun `the shared bounds name this provider's properties`() {
        val stream = valid.stream.copy(idleTimeout = Duration.ZERO)
        val message = message(valid.copy(stream = stream))

        assertEquals("llmgw.providers.anthropic.stream.idle-timeout must be positive.", message)
    }
}
