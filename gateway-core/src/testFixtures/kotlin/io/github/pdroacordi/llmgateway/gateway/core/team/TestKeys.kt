package io.github.pdroacordi.llmgateway.gateway.core.team

import java.time.Instant
import kotlin.uuid.Uuid

/** Fake credentials and the one seeded team. None of these values is a secret anywhere. */
object TestKeys {
    const val TEAM_KEY = "llmgw_test-team-key-not-a-real-secret-00000000000"
    const val OTHER_KEY = "llmgw_test-team-key-not-a-real-secret-11111111111"
    const val UNKNOWN_KEY = "llmgw_test-team-key-not-a-real-secret-99999999999"
    const val BEARER = "Bearer $TEAM_KEY"

    // The same values as both test application.properties.
    const val ADMIN_KEY = "test-admin-key-not-a-real-secret-0001"
    const val ADMIN_BEARER = "Bearer $ADMIN_KEY"
    const val HMAC_SECRET = "test-hmac-secret-not-a-real-secret-0001"

    val TEAM_ID = TeamId(Uuid.parse("01900000-0000-7000-8000-000000000001"))
    val KEY_ID = TeamKeyId(Uuid.parse("01900000-0000-7000-8000-000000000002"))
    val CREATED_AT: Instant = Instant.parse("2026-11-04T12:00:00Z")

    fun name(text: String): ResourceName = checkNotNull(ResourceName.parseOrNull(text)) { "Not a name: $text" }

    fun hasher(): KeyHasher = KeyHasher(HMAC_SECRET.encodeToByteArray())
}
