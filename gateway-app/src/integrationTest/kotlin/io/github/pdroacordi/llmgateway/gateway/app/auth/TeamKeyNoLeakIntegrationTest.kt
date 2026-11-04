package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.core.team.KeyHasher
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.testsupport.SharedPostgres
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource

@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(
    properties = [
        "logging.level.io.github.pdroacordi.llmgateway=DEBUG",
        // Boot's group for org.springframework.http and .web: a first step when debugging a gateway.
        "logging.level.web=TRACE",
    ],
)
class TeamKeyNoLeakIntegrationTest(
    @Autowired private val hasher: KeyHasher,
) : PostgresBackedTest() {
    @Test
    fun `no key, digest or secret reaches the log across create, chat, 401 and 5xx`(output: CapturedOutput) {
        val plain = createKey(createTeam()).get("key").stringValue()

        chatAs(plain).expectStatus().isOk
        chatAs(TestKeys.UNKNOWN_KEY).expectStatus().isUnauthorized
        scenario("""{"otherwise":{"error":{"status":500}}}""")
        chatAs(plain).expectStatus().isEqualTo(502)
        eventually { "with status" in output.all }

        // An absence proves nothing unless the flow was logged at all.
        assertTrue("Key created" in output.all) { "the audit line is missing" }
        listOf(
            plain,
            plain.drop(HINT_LENGTH),
            hasher.digest(plain).hex,
            TestKeys.UNKNOWN_KEY,
            TestKeys.ADMIN_KEY,
            TestKeys.HMAC_SECRET,
        ).forEach { canary -> assertFalse(canary in output.all) { "a credential in the log" } }
    }

    private companion object {
        const val HINT_LENGTH = 10

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            register(registry, SharedPostgres.database("team_key_no_leak"))
        }
    }
}
