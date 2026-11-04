package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import java.time.Instant
import kotlin.uuid.Uuid

@JvmInline
value class TeamId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class TeamKeyId(
    val value: Uuid,
) {
    override fun toString(): String = value.toString()
}

data class Team(
    val id: TeamId,
    val name: ResourceName,
    val createdAt: Instant,
)

/** What the admin API shows of a key. No digest and no plain value. */
data class TeamKey(
    val id: TeamKeyId,
    val team: TeamId,
    val name: ResourceName,
    val hint: String,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

/** What authentication needs of a key. */
data class KeyCredential(
    val team: TeamId,
    val key: TeamKeyId,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

data class NewTeamKey(
    val key: TeamKey,
    val digest: KeyDigest,
    val secretVersion: Short,
)

/** [digest] is for the cache decorator, which must drop the entry (ADR-GW-04-011). */
data class RevokedKey(
    val key: TeamKey,
    val digest: KeyDigest,
)

/** Keyset paging: [after] is the last ID of the previous page. */
data class PageRequest(
    val limit: Int = DEFAULT_LIMIT,
    val after: Uuid? = null,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "A page holds 1 to $MAX_LIMIT items" }
    }

    companion object {
        const val DEFAULT_LIMIT = 20
        const val MAX_LIMIT = 100
    }
}

data class Page<T>(
    val items: List<T>,
    val hasMore: Boolean,
)

sealed interface StoreResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : StoreResult<T>

    data class Failed(
        val error: GatewayError,
    ) : StoreResult<Nothing>
}

/** Never throws for an expected failure; a `Failed` holds a [TeamStoreFailure]. */
interface TeamStorePort {
    /** Hot path. `Ok(null)` is an unknown digest; a revoked or expired row is returned, and core judges it. */
    suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?>

    suspend fun createTeam(team: Team): StoreResult<Team>

    suspend fun listTeams(page: PageRequest): StoreResult<Page<Team>>

    suspend fun createKey(key: NewTeamKey): StoreResult<TeamKey>

    suspend fun listKeys(
        team: TeamId,
        page: PageRequest,
    ): StoreResult<Page<TeamKey>>

    /** Idempotent: a revoked key keeps its first `revokedAt`. */
    suspend fun revokeKey(
        team: TeamId,
        key: TeamKeyId,
        at: Instant,
    ): StoreResult<RevokedKey>
}
