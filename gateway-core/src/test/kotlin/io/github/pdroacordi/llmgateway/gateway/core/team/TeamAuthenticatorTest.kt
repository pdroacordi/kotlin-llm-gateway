package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.error.Unauthenticated
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TeamAuthenticatorTest {
    private val store = InMemoryTeamStore()
    private val hasher = TestKeys.hasher()
    private val authenticator = TeamAuthenticator(hasher, store, Clock.fixed(NOW, ZoneOffset.UTC))

    private fun seed(
        expiresAt: Instant? = null,
        revokedAt: Instant? = null,
    ) {
        store.seed(hasher, expiresAt, revokedAt)
    }

    private val authenticated = Authentication.Authenticated(TestKeys.TEAM_ID, TestKeys.KEY_ID)
    private val invalid = Authentication.Rejected(Unauthenticated.InvalidKey)
    private val missing = Authentication.Rejected(Unauthenticated.MissingKey)

    @Test
    fun `a valid key resolves to its team and key`() =
        runTest {
            seed()

            assertEquals(authenticated, authenticator.authenticate(listOf(TestKeys.BEARER)))
        }

    @Test
    fun `no header is missing and reaches no store`() =
        runTest {
            assertEquals(missing, authenticator.authenticate(emptyList()))
            assertEquals(0, store.findKeyCalls)
        }

    @Test
    fun `another scheme is missing`() =
        runTest {
            assertEquals(missing, authenticator.authenticate(listOf("Basic ${TestKeys.TEAM_KEY}")))
        }

    @Test
    fun `two lines are invalid, even when both hold the good key, and reach no store`() =
        runTest {
            seed()

            assertEquals(invalid, authenticator.authenticate(listOf(TestKeys.BEARER, TestKeys.BEARER)))
            assertEquals(0, store.findKeyCalls)
        }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "abc",
            "llmgw_short",
            "llmgw_test-team-key-not-a-real-secret-000000000000",
            "LLMGW_test-team-key-not-a-real-secret-00000000000",
            "llmgw_test-team-key-not-a-real-secret-0000000000+",
            "llmgw_test-team-key-not-a-real-secret-00000000000, Bearer b",
        ],
    )
    fun `a malformed token is invalid and makes no store call`(token: String) =
        runTest {
            assertEquals(invalid, authenticator.authenticate(listOf("Bearer $token")))
            assertEquals(0, store.findKeyCalls)
        }

    @Test
    fun `an unknown key is invalid`() =
        runTest {
            assertEquals(invalid, authenticator.authenticate(listOf("Bearer ${TestKeys.UNKNOWN_KEY}")))
            assertEquals(1, store.findKeyCalls)
        }

    @Test
    fun `a revoked key is invalid`() =
        runTest {
            seed(revokedAt = NOW.minusSeconds(1))

            assertEquals(invalid, authenticator.authenticate(listOf(TestKeys.BEARER)))
        }

    @Test
    fun `a key at its expiry instant is expired`() =
        runTest {
            seed(expiresAt = NOW)

            assertEquals(invalid, authenticator.authenticate(listOf(TestKeys.BEARER)))
        }

    @Test
    fun `a key just before its expiry passes`() =
        runTest {
            seed(expiresAt = NOW.plusNanos(1_000))

            assertEquals(authenticated, authenticator.authenticate(listOf(TestKeys.BEARER)))
        }

    @Test
    fun `a store failure is the store's error, never a 401`() =
        runTest {
            seed()
            store.available = false

            assertEquals(
                Authentication.Rejected(TeamStoreFailure.Unavailable),
                authenticator.authenticate(listOf(TestKeys.BEARER)),
            )
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-04T12:00:00Z")
    }
}
