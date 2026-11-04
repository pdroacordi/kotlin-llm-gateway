package io.github.pdroacordi.llmgateway.gateway.core.team

import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.request.UuidV7Generator
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

data class CreatedKey(
    val key: TeamKey,
    val secret: TeamKeySecret,
)

/** The admin API's use cases. IDs, times and digests come from here, so the database generates nothing. */
class TeamAdminService(
    private val store: TeamStorePort,
    private val hasher: KeyHasher,
    private val ids: UuidV7Generator,
    private val secrets: Random,
    private val clock: Clock,
) {
    suspend fun createTeam(name: ResourceName): StoreResult<Team> =
        store.createTeam(Team(TeamId(ids.next()), name, clock.instant()))

    suspend fun listTeams(page: PageRequest): StoreResult<Page<Team>> = store.listTeams(page)

    /** The only place a plain key exists after it is generated. */
    suspend fun createKey(
        team: TeamId,
        name: ResourceName,
        expiresAt: Instant?,
    ): StoreResult<CreatedKey> {
        val now = clock.instant()
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return StoreResult.Failed(GatewayError.InvalidRequest.expiresAtInvalid)
        }
        val plain = TeamKeyFormat.generate(secrets)
        val key = TeamKey(TeamKeyId(ids.next()), team, name, TeamKeyFormat.hint(plain), now, expiresAt, null)
        return when (val stored = store.createKey(NewTeamKey(key, hasher.digest(plain), hasher.version))) {
            is StoreResult.Ok -> StoreResult.Ok(CreatedKey(stored.value, TeamKeySecret(plain)))
            is StoreResult.Failed -> stored
        }
    }

    suspend fun listKeys(
        team: TeamId,
        page: PageRequest,
    ): StoreResult<Page<TeamKey>> = store.listKeys(team, page)

    suspend fun revokeKey(
        team: TeamId,
        key: TeamKeyId,
    ): StoreResult<TeamKey> =
        when (val revoked = store.revokeKey(team, key, clock.instant())) {
            is StoreResult.Ok -> StoreResult.Ok(revoked.value.key)
            is StoreResult.Failed -> revoked
        }
}
