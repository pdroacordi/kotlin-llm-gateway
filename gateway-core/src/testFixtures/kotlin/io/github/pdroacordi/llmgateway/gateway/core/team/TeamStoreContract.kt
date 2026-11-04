package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.uuid.Uuid

/**
 * What every caller of [TeamStorePort] relies on, run against the fake and the real adapter (ADR-GW-04-010). A
 * behaviour goes here, never in one subclass. `runBlocking`: `runTest`'s virtual time would fire the deadline of a
 * real adapter.
 */
abstract class TeamStoreContract {
    /** An empty store, created once per test. */
    protected abstract fun store(): TeamStorePort

    private val store: TeamStorePort by lazy { store() }
    private val hasher = TestKeys.hasher()
    private var counter = 0L

    private fun nextId(): Uuid = Uuid.fromLongs(++counter, Long.MIN_VALUE)

    private fun team(name: String = "team-${++counter}"): Team = Team(TeamId(nextId()), TestKeys.name(name), NOW)

    private fun key(
        team: TeamId,
        name: String = "key-${++counter}",
        expiresAt: Instant? = null,
    ): NewTeamKey {
        val key = TeamKey(TeamKeyId(nextId()), team, TestKeys.name(name), "llmgw_Ab3d", NOW, expiresAt, null)
        return NewTeamKey(key, hasher.digest("token-${key.id}"), 1)
    }

    private fun <T> StoreResult<T>.ok(): T =
        when (this) {
            is StoreResult.Ok -> value
            is StoreResult.Failed -> error("Expected Ok, got $error")
        }

    private fun StoreResult<*>.failure(): GatewayError =
        when (this) {
            is StoreResult.Ok -> error("Expected Failed, got $value")
            is StoreResult.Failed -> error
        }

    private suspend fun createdTeam(): Team = store.createTeam(team()).ok()

    @Test
    fun `an unknown digest is Ok null`() =
        runBlocking {
            assertNull(store.findKey(hasher.digest("nobody")).ok())
        }

    @Test
    fun `a created team is returned as stored`() =
        runBlocking {
            val team = team()

            assertEquals(team, store.createTeam(team).ok())
        }

    @Test
    fun `a team name in use is TeamNameTaken`() =
        runBlocking {
            store.createTeam(team("payments")).ok()

            assertEquals(TeamStoreFailure.TeamNameTaken, store.createTeam(team("payments")).failure())
        }

    @Test
    fun `a created key is returned as stored and found by digest`() =
        runBlocking {
            val team = createdTeam()
            val key = key(team.id, expiresAt = LATER)

            assertEquals(key.key, store.createKey(key).ok())
            assertEquals(KeyCredential(team.id, key.key.id, LATER, null), store.findKey(key.digest).ok())
        }

    @Test
    fun `an instant keeps microseconds and loses the rest, as timestamptz does`() =
        runBlocking {
            val team = createdTeam()
            val key = key(team.id, expiresAt = Instant.parse("2027-01-01T00:00:00.123456789Z"))

            val created = store.createKey(key).ok()

            assertEquals(Instant.parse("2027-01-01T00:00:00.123456Z"), created.expiresAt)
        }

    @Test
    fun `a key for an unknown team is TeamNotFound`() =
        runBlocking {
            val key = key(TeamId(nextId()))

            assertEquals(TeamStoreFailure.TeamNotFound, store.createKey(key).failure())
        }

    @Test
    fun `a key name in use by an unrevoked key of the team is KeyNameTaken`() =
        runBlocking {
            val team = createdTeam()
            store.createKey(key(team.id, "ci")).ok()

            assertEquals(TeamStoreFailure.KeyNameTaken, store.createKey(key(team.id, "ci")).failure())
        }

    @Test
    fun `a key name is free again once its key is revoked`() =
        runBlocking {
            val team = createdTeam()
            val first = store.createKey(key(team.id, "ci")).ok()
            store.revokeKey(team.id, first.id, NOW).ok()

            val second = key(team.id, "ci")

            assertEquals(second.key, store.createKey(second).ok())
        }

    @Test
    fun `two teams can use one key name`() =
        runBlocking {
            store.createKey(key(createdTeam().id, "ci")).ok()

            val other = key(createdTeam().id, "ci")

            assertEquals(other.key, store.createKey(other).ok())
        }

    @Test
    fun `revoking sets revokedAt, returns the digest, and findKey still returns the row`() =
        runBlocking {
            val team = createdTeam()
            val key = key(team.id)
            store.createKey(key).ok()

            val revoked = store.revokeKey(team.id, key.key.id, LATER).ok()

            assertEquals(RevokedKey(key.key.copy(revokedAt = LATER), key.digest), revoked)
            assertEquals(LATER, store.findKey(key.digest).ok()?.revokedAt)
        }

    @Test
    fun `a second revoke keeps the first revokedAt`() =
        runBlocking {
            val team = createdTeam()
            val key = key(team.id)
            store.createKey(key).ok()
            store.revokeKey(team.id, key.key.id, NOW).ok()

            val again = store.revokeKey(team.id, key.key.id, LATER).ok()

            assertEquals(NOW, again.key.revokedAt)
        }

    @Test
    fun `an unknown key is KeyNotFound on revoke`() =
        runBlocking {
            val team = createdTeam()

            assertEquals(
                TeamStoreFailure.KeyNotFound,
                store.revokeKey(team.id, TeamKeyId(nextId()), NOW).failure(),
            )
        }

    @Test
    fun `another team's key is KeyNotFound on revoke and stays unrevoked`() =
        runBlocking {
            val owner = createdTeam()
            val key = key(owner.id)
            store.createKey(key).ok()

            val failure = store.revokeKey(createdTeam().id, key.key.id, NOW).failure()

            assertEquals(TeamStoreFailure.KeyNotFound, failure)
            assertNull(store.findKey(key.digest).ok()?.revokedAt)
        }

    @Test
    fun `an expired key is still returned, for core to judge`() =
        runBlocking {
            val team = createdTeam()
            val key = key(team.id, expiresAt = NOW.minusSeconds(1))
            store.createKey(key).ok()

            assertNotNull(store.findKey(key.digest).ok())
        }

    @Test
    fun `teams list ascending by ID in pages, with hasMore`() =
        runBlocking {
            val teams = List(5) { createdTeam() }

            val first = store.listTeams(PageRequest(2)).ok()
            val second =
                store
                    .listTeams(
                        PageRequest(
                            2,
                            first.items
                                .last()
                                .id.value,
                        ),
                    ).ok()
            val third =
                store
                    .listTeams(
                        PageRequest(
                            2,
                            second.items
                                .last()
                                .id.value,
                        ),
                    ).ok()

            assertEquals(teams.take(2), first.items)
            assertTrue(first.hasMore)
            assertEquals(teams.subList(2, 4), second.items)
            assertTrue(second.hasMore)
            assertEquals(teams.drop(4), third.items)
            assertFalse(third.hasMore)
        }

    @Test
    fun `a page that exactly ends the list has no more`() =
        runBlocking {
            val teams = List(2) { createdTeam() }

            val page = store.listTeams(PageRequest(2)).ok()

            assertEquals(teams, page.items)
            assertFalse(page.hasMore)
        }

    @Test
    fun `no teams is an empty page`() =
        runBlocking {
            assertEquals(Page(emptyList<Team>(), false), store.listTeams(PageRequest()).ok())
        }

    @Test
    fun `a team's keys list ascending by ID, revoked ones included, and only that team's`() =
        runBlocking {
            val team = createdTeam()
            val keys = List(3) { key(team.id).also { key -> store.createKey(key).ok() } }
            store.createKey(key(createdTeam().id)).ok()
            val revoked = store.revokeKey(team.id, keys[1].key.id, NOW).ok()

            val first = store.listKeys(team.id, PageRequest(2)).ok()
            val second =
                store
                    .listKeys(
                        team.id,
                        PageRequest(
                            2,
                            first.items
                                .last()
                                .id.value,
                        ),
                    ).ok()

            assertEquals(listOf(keys[0].key, revoked.key), first.items)
            assertTrue(first.hasMore)
            assertEquals(listOf(keys[2].key), second.items)
            assertFalse(second.hasMore)
        }

    @Test
    fun `an unknown team is TeamNotFound on list and a known one with no keys an empty page`() =
        runBlocking {
            val team = createdTeam()

            assertEquals(Page(emptyList<TeamKey>(), false), store.listKeys(team.id, PageRequest()).ok())
            assertEquals(TeamStoreFailure.TeamNotFound, store.listKeys(TeamId(nextId()), PageRequest()).failure())
        }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-04T12:00:00.123456Z")
        val LATER: Instant = Instant.parse("2026-11-05T12:00:00.654321Z")
    }
}
