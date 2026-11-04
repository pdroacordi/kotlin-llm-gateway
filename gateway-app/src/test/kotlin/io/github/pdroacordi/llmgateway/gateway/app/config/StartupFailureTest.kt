package io.github.pdroacordi.llmgateway.gateway.app.config

import io.github.pdroacordi.llmgateway.gateway.app.isolatedGateway
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import java.time.Duration

@ExtendWith(OutputCaptureExtension::class)
class StartupFailureTest {
    private fun start(vararg args: String): Throwable =
        assertThrows<Throwable> {
            isolatedGateway().run("--server.port=0", *args).close()
        }

    private fun Throwable.chain(): String =
        generateSequence(this) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `a blank key stops the app with a message naming the variable`(output: CapturedOutput) {
        val failure = start("--llmgw.providers.openai.api-key=")

        assertTrue("OPENAI_API_KEY" in failure.chain()) { failure.chain() }
        assertFalse("test-key-not-real" in output.all)
    }

    @Test
    fun `a key with a trailing newline stops the app with a message naming the variable`(output: CapturedOutput) {
        val failure = start("--llmgw.providers.openai.api-key=$CANARY\n")

        assertTrue("OPENAI_API_KEY" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `an invalid timeout stops the app and the output does not hold the key`(output: CapturedOutput) {
        val failure =
            start(
                "--llmgw.providers.openai.api-key=$CANARY",
                "--llmgw.providers.openai.request-timeout=not-a-duration",
            )

        assertTrue("request-timeout" in failure.chain() + output.all) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `a value toAdapterConfig rejects stops the app and the output does not hold the key`(output: CapturedOutput) {
        val failure =
            start(
                "--llmgw.providers.openai.api-key=$CANARY",
                "--llmgw.providers.openai.request-timeout=0s",
            )

        assertTrue("request-timeout" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `a blank HMAC secret stops the app, naming the property and the variable`() {
        val failure = start("--llmgw.auth.hmac-secret=")

        assertTrue("llmgw.auth.hmac-secret" in failure.chain()) { failure.chain() }
        assertTrue("LLMGW_KEY_HMAC_SECRET" in failure.chain()) { failure.chain() }
    }

    @Test
    fun `a short HMAC secret stops the app and neither the failure nor the output holds it`(output: CapturedOutput) {
        val failure = start("--llmgw.auth.hmac-secret=$CANARY")

        assertTrue("llmgw.auth.hmac-secret" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `a non-ASCII HMAC secret stops the app and neither the failure nor the output holds it`(
        output: CapturedOutput,
    ) {
        val secret = "$LONG_CANARY-ç"
        val failure = start("--llmgw.auth.hmac-secret=$secret")

        assertTrue("llmgw.auth.hmac-secret" in failure.chain()) { failure.chain() }
        assertFalse(LONG_CANARY in failure.chain()) { failure.chain() }
        assertFalse(LONG_CANARY in output.all) { output.all }
    }

    @Test
    fun `a blank admin key stops the app, naming the property and the variable`() {
        val failure = start("--llmgw.auth.admin-key=")

        assertTrue("llmgw.auth.admin-key" in failure.chain()) { failure.chain() }
        assertTrue("LLMGW_ADMIN_KEY" in failure.chain()) { failure.chain() }
    }

    @Test
    fun `a short admin key stops the app and neither the failure nor the output holds it`(output: CapturedOutput) {
        val failure = start("--llmgw.auth.admin-key=$CANARY")

        assertTrue("llmgw.auth.admin-key" in failure.chain()) { failure.chain() }
        assertFalse(CANARY in failure.chain()) { failure.chain() }
        assertFalse(CANARY in output.all) { output.all }
    }

    @Test
    fun `equal secrets stop the app and the output does not hold them`(output: CapturedOutput) {
        val failure = start("--llmgw.auth.hmac-secret=$LONG_CANARY", "--llmgw.auth.admin-key=$LONG_CANARY")

        assertTrue("llmgw.auth.admin-key" in failure.chain()) { failure.chain() }
        assertFalse(LONG_CANARY in failure.chain()) { failure.chain() }
        assertFalse(LONG_CANARY in output.all) { output.all }
    }

    @Test
    fun `a cache TTL of 0s stops the app, naming the property`() {
        val failure = start("--llmgw.auth.cache.ttl=0s")

        assertTrue("llmgw.auth.cache.ttl" in failure.chain()) { failure.chain() }
    }

    @Test
    fun `a bad neighbour property stops the app and the output does not hold the secret`(output: CapturedOutput) {
        val failure = start("--llmgw.auth.hmac-secret=$LONG_CANARY", "--llmgw.auth.cache.ttl=not-a-duration")

        assertTrue("llmgw.auth.cache.ttl" in failure.chain() + output.all) { failure.chain() }
        assertFalse(LONG_CANARY in failure.chain()) { failure.chain() }
        assertFalse(LONG_CANARY in output.all) { output.all }
    }

    @ParameterizedTest
    @CsvSource(
        "0.0.0.0, llmgw.auth.hmac-secret, local-profile-hmac-secret-not-a-secret",
        "192.0.2.1, llmgw.auth.hmac-secret, local-profile-hmac-secret-not-a-secret",
        "0.0.0.0, llmgw.auth.admin-key, local-profile-admin-key-not-a-secret",
    )
    fun `a published local secret past loopback stops the app, naming only the properties`(
        address: String,
        property: String,
        value: String,
        output: CapturedOutput,
    ) {
        val failure = start("--server.address=$address", "--$property=$value")

        assertTrue(property in failure.chain()) { failure.chain() }
        assertTrue("server.address" in failure.chain()) { failure.chain() }
        assertFalse(value in failure.chain()) { failure.chain() }
        assertFalse(value in output.all) { output.all }
    }

    @Test
    fun `the local profile past loopback stops the app`() {
        val failure =
            assertThrows<Throwable> {
                isolatedGateway().profiles("local").run("--server.port=0", "--server.address=0.0.0.0").close()
            }

        assertTrue("llmgw.auth.hmac-secret" in failure.chain()) { failure.chain() }
    }

    @Test
    fun `a hung Postgres stops the start at Flyway`() {
        BlackHoleServer().use { server ->
            assertTimeoutPreemptively(Duration.ofSeconds(HUNG_START_LIMIT_SECONDS)) {
                start(
                    "--spring.flyway.enabled=true",
                    "--LLMGW_POSTGRES_PORT=${server.port}",
                    "--LLMGW_FLYWAY_SOCKET_TIMEOUT=1",
                )
            }
        }
    }

    @Test
    fun `the local profile starts with no secret variable set`() {
        val context =
            isolatedGateway()
                .profiles("local")
                .run("--server.port=0")

        context.use { assertTrue(it.isRunning) }
    }

    private companion object {
        // A few reads of 1 s each. A hang past this is a missing timeout.
        const val HUNG_START_LIMIT_SECONDS = 20L
        const val CANARY = "sk-canary-key"
        const val LONG_CANARY = "sk-canary-key-long-enough-to-pass-the-length-check"
    }
}
