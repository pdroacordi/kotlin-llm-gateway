package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.UuidV7Generator
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random

class TeamAdminServiceTest {
    private val store = InMemoryTeamStore()
    private val hasher = KeyHasher(TestKeys.HMAC_SECRET.encodeToByteArray(), SECRET_VERSION)
    private val clock = Clock.fixed(NOW, ZoneOffset.UTC)
    private val service = TeamAdminService(store, hasher, UuidV7Generator(clock, Random(1)), Random(2), clock)

    private fun <T> StoreResult<T>.ok(): T = (this as StoreResult.Ok).value

    private suspend fun team(): Team = service.createTeam(TestKeys.name("payments")).ok()

    private suspend fun created(expiresAt: Instant? = null): CreatedKey =
        service.createKey(team().id, TestKeys.name("ci"), expiresAt).ok()

    @Test
    fun `a team gets a UUIDv7 and the clock's time`() =
        runTest {
            val team = team()

            assertEquals(NOW, team.createdAt)
            assertEquals('7', team.id.toString()[VERSION_INDEX])
        }

    @Test
    fun `a key is llmgw_ plus 43 base64url characters`() =
        runTest {
            val plain = created().secret.reveal()

            assertTrue(Regex("llmgw_[A-Za-z0-9_-]{43}").matches(plain)) { "the key shape is wrong" }
        }

    @Test
    fun `two keys differ`() =
        runTest {
            val team = team()

            val first = service.createKey(team.id, TestKeys.name("a"), null).ok()
            val second = service.createKey(team.id, TestKeys.name("b"), null).ok()

            assertNotEquals(first.secret.reveal(), second.secret.reveal())
        }

    @Test
    fun `the hint is the prefix and the first 4 secret characters`() =
        runTest {
            val created = created()

            assertEquals(created.secret.reveal().take(10), created.key.hint)
        }

    @Test
    fun `the stored digest is KeyHasher's of the plain value, with the hasher's version`() =
        runTest {
            val created = created()

            val found = store.findKey(hasher.digest(created.secret.reveal())).ok()

            assertEquals(created.key.id, found?.key)
            assertEquals(SECRET_VERSION, store.secretVersionOf(created.key.id))
        }

    @Test
    fun `a future expiry is kept`() =
        runTest {
            val expiry = NOW.plusSeconds(60)

            assertEquals(expiry, created(expiry).key.expiresAt)
        }

    @Test
    fun `an expiry at or before now is refused with param expires_at and nothing stored`() =
        runTest {
            val team = team()

            listOf(NOW, NOW.minusSeconds(1)).forEach { expiry ->
                val failure = service.createKey(team.id, TestKeys.name("ci"), expiry) as StoreResult.Failed

                assertEquals("expires_at", (failure.error as GatewayError.InvalidRequest).param)
            }
            assertTrue(
                store
                    .listKeys(team.id, PageRequest())
                    .ok()
                    .items
                    .isEmpty(),
            )
        }

    @Test
    fun `a store failure on create is the store's failure`() =
        runTest {
            val failure = service.createKey(TestKeys.TEAM_ID, TestKeys.name("ci"), null)

            assertEquals(StoreResult.Failed(TeamStoreFailure.TeamNotFound), failure)
        }

    @Test
    fun `a created key prints no plain value`() =
        runTest {
            val created = created()

            assertFalse(created.secret.reveal() in created.toString())
        }

    @Test
    fun `revoking stamps the clock's time and returns the key`() =
        runTest {
            val created = created()
            assertNull(created.key.revokedAt)

            val revoked = service.revokeKey(created.key.team, created.key.id).ok()

            assertEquals(NOW, revoked.revokedAt)
        }

    @Test
    fun `a failed revoke is the store's failure`() =
        runTest {
            val team = team()

            assertEquals(
                StoreResult.Failed(TeamStoreFailure.KeyNotFound),
                service.revokeKey(team.id, TestKeys.KEY_ID),
            )
        }

    @Test
    fun `lists pass through`() =
        runTest {
            val created = created()

            assertEquals(
                listOf(created.key.team),
                service
                    .listTeams(PageRequest())
                    .ok()
                    .items
                    .map { it.id },
            )
            assertEquals(
                listOf(created.key.id),
                service
                    .listKeys(created.key.team, PageRequest())
                    .ok()
                    .items
                    .map { it.id },
            )
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-04T12:00:00Z")
        const val SECRET_VERSION: Short = 3

        // The version digit in the canonical 8-4-4-4-12 text.
        const val VERSION_INDEX = 14
    }
}
