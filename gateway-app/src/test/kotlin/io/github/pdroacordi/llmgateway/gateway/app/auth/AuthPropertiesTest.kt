package io.github.pdroacordi.llmgateway.gateway.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class AuthPropertiesTest {
    private val cache = AuthProperties.CacheProperties(Duration.ofSeconds(30), 10_000, Duration.ofSeconds(5), 10_000)
    private val valid =
        AuthProperties(
            ConfiguredSecret(SECRET),
            ConfiguredSecret(OTHER),
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            cache,
        )

    private fun refused(properties: AuthProperties): String =
        assertThrows<IllegalStateException> { properties.validate() }.message.orEmpty()

    @Test
    fun `the defaults pass`() {
        assertDoesNotThrow { valid.validate() }
    }

    @Test
    fun `the bounds pass`() {
        val edges =
            valid.copy(
                hmacSecret = ConfiguredSecret("a".repeat(32)),
                adminKey = ConfiguredSecret("b".repeat(512)),
                storeTimeout = Duration.ofSeconds(10),
                warmupTimeout = Duration.ofMinutes(2),
                cache = cache.copy(ttl = Duration.ofMinutes(5), negativeTtl = Duration.ofMinutes(1)),
            )

        assertDoesNotThrow { edges.validate() }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "   ",
            "short",
            "has a space in it, and is long enough to pass",
            "long-enough-but-ends-in-a-newline-as-echo-writes-it\n",
        ],
    )
    fun `a blank, short or non-visible hmac secret names the property and the variable`(value: String) {
        val message = refused(valid.copy(hmacSecret = ConfiguredSecret(value)))

        assertTrue("llmgw.auth.hmac-secret" in message) { message }
        assertTrue("LLMGW_KEY_HMAC_SECRET" in message) { message }
    }

    @Test
    fun `a zero or too long warm-up timeout names the property`() {
        listOf(Duration.ZERO, Duration.ofSeconds(121)).forEach { value ->
            val message = refused(valid.copy(warmupTimeout = value))

            assertTrue("llmgw.auth.warmup-timeout" in message) { message }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "short", "chave-com-acento-çççççççççççççççççççççççç"])
    fun `a blank, short or non-ASCII admin key names the property and the variable`(value: String) {
        val message = refused(valid.copy(adminKey = ConfiguredSecret(value)))

        assertTrue("llmgw.auth.admin-key" in message) { message }
        assertTrue("LLMGW_ADMIN_KEY" in message) { message }
    }

    @Test
    fun `a value over 512 characters is refused`() {
        val message = refused(valid.copy(adminKey = ConfiguredSecret("a".repeat(513))))

        assertTrue("llmgw.auth.admin-key" in message) { message }
    }

    @Test
    fun `equal values are refused, and neither is quoted`() {
        val message = refused(valid.copy(adminKey = ConfiguredSecret(SECRET)))

        assertTrue("llmgw.auth.admin-key" in message) { message }
        assertTrue("llmgw.auth.hmac-secret" in message) { message }
        assertFalse(SECRET in message) { message }
    }

    @Test
    fun `a store timeout that is not positive or over 10s is refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(11)).forEach { timeout ->
            val message = refused(valid.copy(storeTimeout = timeout))

            assertTrue("llmgw.auth.store-timeout" in message) { message }
        }
    }

    @Test
    fun `a cache TTL that is not positive or over 5m is refused`() {
        listOf(Duration.ZERO, Duration.ofMinutes(6)).forEach { ttl ->
            val message = refused(valid.copy(cache = cache.copy(ttl = ttl)))

            assertTrue("llmgw.auth.cache.ttl" in message) { message }
        }
    }

    @Test
    fun `a negative TTL that is not positive or over 1m is refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(61)).forEach { ttl ->
            val message = refused(valid.copy(cache = cache.copy(negativeTtl = ttl)))

            assertTrue("llmgw.auth.cache.negative-ttl" in message) { message }
        }
    }

    @Test
    fun `sizes must be positive`() {
        assertTrue("llmgw.auth.cache.max-size" in refused(valid.copy(cache = cache.copy(maxSize = 0))))
        assertTrue(
            "llmgw.auth.cache.negative-max-size" in refused(valid.copy(cache = cache.copy(negativeMaxSize = 0))),
        )
    }

    @Test
    fun `the data class prints no secret`() {
        assertFalse(SECRET in valid.toString())
        assertFalse(OTHER in valid.toString())
    }

    @Test
    fun `the secret's bytes are what reveal returns`() {
        assertEquals(SECRET, ConfiguredSecret(SECRET).reveal())
    }

    private companion object {
        const val SECRET = "fake-hmac-secret-of-at-least-32-characters"
        const val OTHER = "fake-admin-key-of-at-least-32-characters"
    }
}
