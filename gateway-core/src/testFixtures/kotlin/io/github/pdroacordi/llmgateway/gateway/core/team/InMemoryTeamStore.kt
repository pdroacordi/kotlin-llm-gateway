package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** The fake of [TeamStorePort]; [TeamStoreContract] proves it equal to the real adapter (ADR-GW-04-010). */
class InMemoryTeamStore : TeamStorePort {
    private class Row(
        val key: TeamKey,
        val digest: KeyDigest,
        val secretVersion: Short,
    )

    private val teams = ConcurrentHashMap<TeamId, Team>()
    private val keys = ConcurrentHashMap<TeamKeyId, Row>()
    private val lookups = AtomicInteger()

    /** False makes every operation fail as an outage does. */
    @Volatile
    var available: Boolean = true

    val findKeyCalls: Int get() = lookups.get()

    fun secretVersionOf(key: TeamKeyId): Short? = keys[key]?.secretVersion

    /** Adds the team and key of [TestKeys], digested by [hasher]. */
    fun seed(
        hasher: KeyHasher,
        expiresAt: Instant? = null,
        revokedAt: Instant? = null,
    ) {
        teams[TestKeys.TEAM_ID] = Team(TestKeys.TEAM_ID, TestKeys.name("seeded-team"), TestKeys.CREATED_AT)
        val key =
            TeamKey(
                TestKeys.KEY_ID,
                TestKeys.TEAM_ID,
                TestKeys.name("seeded-key"),
                TestKeys.TEAM_KEY.take(HINT_LENGTH),
                TestKeys.CREATED_AT,
                expiresAt,
                revokedAt,
            )
        keys[key.id] = Row(key, hasher.digest(TestKeys.TEAM_KEY), hasher.version)
    }

    override suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?> {
        lookups.incrementAndGet()
        return whenAvailable {
            val key = keys.values.firstOrNull { it.digest == digest }?.key
            StoreResult.Ok(key?.let { KeyCredential(it.team, it.id, it.expiresAt, it.revokedAt) })
        }
    }

    override suspend fun createTeam(team: Team): StoreResult<Team> =
        whenAvailable {
            synchronized(this) {
                if (teams.values.any { it.name == team.name }) {
                    StoreResult.Failed(TeamStoreFailure.TeamNameTaken)
                } else {
                    val stored = team.copy(createdAt = team.createdAt.stored())
                    teams[team.id] = stored
                    StoreResult.Ok(stored)
                }
            }
        }

    override suspend fun listTeams(page: PageRequest): StoreResult<Page<Team>> =
        whenAvailable { StoreResult.Ok(page(teams.values, page) { it.id.value }) }

    override suspend fun createKey(key: NewTeamKey): StoreResult<TeamKey> =
        whenAvailable {
            synchronized(this) {
                val new = key.key
                val taken = keys.values.any { it.key.team == new.team && it.key.name == new.name && it.isActive }
                when {
                    !teams.containsKey(new.team) -> {
                        StoreResult.Failed(TeamStoreFailure.TeamNotFound)
                    }

                    taken -> {
                        StoreResult.Failed(TeamStoreFailure.KeyNameTaken)
                    }

                    else -> {
                        val stored =
                            new.copy(createdAt = new.createdAt.stored(), expiresAt = new.expiresAt?.stored())
                        keys[new.id] = Row(stored, key.digest, key.secretVersion)
                        StoreResult.Ok(stored)
                    }
                }
            }
        }

    override suspend fun listKeys(
        team: TeamId,
        page: PageRequest,
    ): StoreResult<Page<TeamKey>> =
        whenAvailable {
            if (!teams.containsKey(team)) {
                StoreResult.Failed(TeamStoreFailure.TeamNotFound)
            } else {
                StoreResult.Ok(page(keys.values.map { it.key }.filter { it.team == team }, page) { it.id.value })
            }
        }

    override suspend fun revokeKey(
        team: TeamId,
        key: TeamKeyId,
        at: Instant,
    ): StoreResult<RevokedKey> =
        whenAvailable {
            synchronized(this) {
                val row = keys[key]?.takeIf { it.key.team == team }
                if (row == null) {
                    StoreResult.Failed(TeamStoreFailure.KeyNotFound)
                } else {
                    val revoked = row.key.copy(revokedAt = row.key.revokedAt ?: at.stored())
                    keys[key] = Row(revoked, row.digest, row.secretVersion)
                    StoreResult.Ok(RevokedKey(revoked, row.digest))
                }
            }
        }

    private val Row.isActive: Boolean get() = key.revokedAt == null

    private fun <T> whenAvailable(answer: () -> StoreResult<T>): StoreResult<T> =
        if (available) answer() else StoreResult.Failed(TeamStoreFailure.Unavailable)

    // The text form sorts as Postgres sorts uuid: by unsigned bytes.
    private fun <T> page(
        items: Collection<T>,
        page: PageRequest,
        id: (T) -> Uuid,
    ): Page<T> {
        val after = page.after?.toString().orEmpty()
        val sorted = items.sortedBy { id(it).toString() }.filter { id(it).toString() > after }
        return Page(sorted.take(page.limit), sorted.size > page.limit)
    }

    // timestamptz keeps microseconds.
    private fun Instant.stored(): Instant = truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val HINT_LENGTH = 10
    }
}
