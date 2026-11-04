package io.github.pdroacordi.llmgateway.gateway.app.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.time.Duration

class PublishedSecretsTest {
    private val cache = AuthProperties.CacheProperties(Duration.ofSeconds(30), 1, Duration.ofSeconds(5), 1)

    private fun properties(
        hmacSecret: String = "own-hmac-secret-of-at-least-32-characters",
        adminKey: String = "own-admin-key-of-at-least-32-characters",
    ) = AuthProperties(
        ConfiguredSecret(hmacSecret),
        ConfiguredSecret(adminKey),
        Duration.ofSeconds(1),
        Duration.ofSeconds(10),
        cache,
    )

    @Test
    fun `the published values are the ones in application-local yaml`() {
        val local =
            YamlPropertySourceLoader()
                .load("local", ClassPathResource("application-local.yaml"))
                .single()

        assertEquals(PublishedSecrets.HMAC_SECRET, local.getProperty("llmgw.auth.hmac-secret").toString())
        assertEquals(PublishedSecrets.ADMIN_KEY, local.getProperty("llmgw.auth.admin-key").toString())
    }

    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1", "127.8.8.8", "::1", "[::1]", "localhost", "LOCALHOST"])
    fun `a published value on loopback passes`(address: String) {
        val published = properties(PublishedSecrets.HMAC_SECRET, PublishedSecrets.ADMIN_KEY)

        assertDoesNotThrow { PublishedSecrets.check(published, address) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.0.0.0", "::", "192.0.2.1", "gateway.example", "localhost.example", ""])
    fun `a published value on any other address names the property and never the value`(address: String) {
        val hmac = properties(hmacSecret = PublishedSecrets.HMAC_SECRET)
        val admin = properties(adminKey = PublishedSecrets.ADMIN_KEY)

        val hmacFailure = assertThrows<IllegalStateException> { PublishedSecrets.check(hmac, address) }
        val adminFailure = assertThrows<IllegalStateException> { PublishedSecrets.check(admin, address) }
        val hmacMessage = hmacFailure.message.orEmpty()
        val adminMessage = adminFailure.message.orEmpty()

        assertTrue("llmgw.auth.hmac-secret" in hmacMessage) { hmacMessage }
        assertTrue("llmgw.auth.admin-key" in adminMessage) { adminMessage }
        assertFalse(PublishedSecrets.HMAC_SECRET in hmacMessage) { hmacMessage }
        assertFalse(PublishedSecrets.ADMIN_KEY in adminMessage) { adminMessage }
    }

    @Test
    fun `no address means every interface`() {
        assertThrows<IllegalStateException> {
            PublishedSecrets.check(properties(adminKey = PublishedSecrets.ADMIN_KEY), null)
        }
    }

    @Test
    fun `own values pass on any address`() {
        assertDoesNotThrow { PublishedSecrets.check(properties(), "0.0.0.0") }
        assertDoesNotThrow { PublishedSecrets.check(properties(), null) }
    }
}
