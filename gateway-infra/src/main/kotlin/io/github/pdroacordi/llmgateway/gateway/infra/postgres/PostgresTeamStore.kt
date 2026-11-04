package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyCredential
import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.NewTeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.Page
import io.github.pdroacordi.llmgateway.gateway.core.team.PageRequest
import io.github.pdroacordi.llmgateway.gateway.core.team.RevokedKey
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.Team
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamStorePort
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.R2dbcException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.NonTransientDataAccessResourceException
import org.springframework.dao.TransientDataAccessException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import reactor.core.publisher.Mono
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * The only user of [DatabaseClient]. Every write is one statement, so no transaction exists and no read-then-write race
 * (GW-04 section 5). [timeout] covers acquire and query together, per operation.
 */
class PostgresTeamStore(
    connections: ConnectionFactory,
    private val timeout: Duration,
) : TeamStorePort {
    private val client = DatabaseClient.create(connections)

    override suspend fun findKey(digest: KeyDigest): StoreResult<KeyCredential?> =
        guarded {
            val credential =
                client
                    .sql("SELECT id, team_id, expires_at, revoked_at FROM team_keys WHERE digest = :digest")
                    .bind("digest", digest.bytes())
                    .map { row, _ ->
                        KeyCredential(
                            TeamId(row.uuid("team_id")),
                            TeamKeyId(row.uuid("id")),
                            row.instantOrNull("expires_at"),
                            row.instantOrNull("revoked_at"),
                        )
                    }.awaitOneOrNull()
            StoreResult.Ok(credential)
        }

    override suspend fun createTeam(team: Team): StoreResult<Team> =
        guarded {
            val created =
                client
                    .sql(
                        """
                        INSERT INTO teams (id, name, created_at) VALUES (:id, :name, :created_at)
                        ON CONFLICT (name) DO NOTHING
                        RETURNING $TEAM_COLUMNS
                        """.trimIndent(),
                    ).bind("id", team.id.value.toJavaUuid())
                    .bind("name", team.name.value)
                    .bind("created_at", team.createdAt.stored())
                    .map { row, _ -> row.team() }
                    .awaitOneOrNull()
            if (created == null) StoreResult.Failed(TeamStoreFailure.TeamNameTaken) else StoreResult.Ok(created)
        }

    override suspend fun listTeams(page: PageRequest): StoreResult<Page<Team>> =
        guarded {
            val rows =
                client
                    .sql("SELECT $TEAM_COLUMNS FROM teams WHERE id > :after ORDER BY id LIMIT :limit")
                    .bindPage(page)
                    .map { row, _ -> row.team() }
                    .flow()
                    .toList()
            StoreResult.Ok(rows.toPage(page))
        }

    override suspend fun createKey(key: NewTeamKey): StoreResult<TeamKey> =
        guarded {
            try {
                val created = insert(key)
                if (created == null) StoreResult.Failed(TeamStoreFailure.KeyNameTaken) else StoreResult.Ok(created)
            } catch (failure: DataIntegrityViolationException) {
                if (failure.sqlState != FOREIGN_KEY_VIOLATION) throw failure
                StoreResult.Failed(TeamStoreFailure.TeamNotFound)
            }
        }

    private suspend fun insert(new: NewTeamKey): TeamKey? {
        val key = new.key
        val statement =
            client
                .sql(
                    """
                    INSERT INTO team_keys (id, team_id, name, digest, secret_version, hint, created_at, expires_at)
                    VALUES (:id, :team_id, :name, :digest, :secret_version, :hint, :created_at, :expires_at)
                    ON CONFLICT (team_id, name) WHERE revoked_at IS NULL DO NOTHING
                    RETURNING $KEY_COLUMNS
                    """.trimIndent(),
                ).bind("id", key.id.value.toJavaUuid())
                .bind("team_id", key.team.value.toJavaUuid())
                .bind("name", key.name.value)
                .bind("digest", new.digest.bytes())
                .bind("secret_version", new.secretVersion)
                .bind("hint", key.hint)
                .bind("created_at", key.createdAt.stored())
        val expiring =
            key.expiresAt?.let { statement.bind("expires_at", it.stored()) }
                ?: statement.bindNull("expires_at", Instant::class.java)
        return expiring.map { row, _ -> row.teamKey() }.awaitOneOrNull()
    }

    override suspend fun listKeys(
        team: TeamId,
        page: PageRequest,
    ): StoreResult<Page<TeamKey>> =
        guarded {
            val rows =
                client
                    .sql(
                        """
                        SELECT $KEY_COLUMNS FROM team_keys
                        WHERE team_id = :team_id AND id > :after ORDER BY id LIMIT :limit
                        """.trimIndent(),
                    ).bind("team_id", team.value.toJavaUuid())
                    .bindPage(page)
                    .map { row, _ -> row.teamKey() }
                    .flow()
                    .toList()
            if (rows.isEmpty() && !exists(team)) {
                StoreResult.Failed(TeamStoreFailure.TeamNotFound)
            } else {
                StoreResult.Ok(rows.toPage(page))
            }
        }

    private suspend fun exists(team: TeamId): Boolean =
        client
            .sql("SELECT 1 AS found FROM teams WHERE id = :id")
            .bind("id", team.value.toJavaUuid())
            .map { row, _ -> row.get("found") }
            .awaitSingleOrNull() != null

    override suspend fun revokeKey(
        team: TeamId,
        key: TeamKeyId,
        at: Instant,
    ): StoreResult<RevokedKey> =
        guarded {
            val revoked =
                client
                    .sql(
                        """
                        UPDATE team_keys SET revoked_at = COALESCE(revoked_at, :at)
                        WHERE id = :id AND team_id = :team_id
                        RETURNING $KEY_COLUMNS, digest
                        """.trimIndent(),
                    ).bind("at", at.stored())
                    .bind("id", key.value.toJavaUuid())
                    .bind("team_id", team.value.toJavaUuid())
                    .map { row, _ -> RevokedKey(row.teamKey(), row.digest()) }
                    .awaitOneOrNull()
            if (revoked == null) StoreResult.Failed(TeamStoreFailure.KeyNotFound) else StoreResult.Ok(revoked)
        }

    // Only an outage is Unavailable: grammar and mapping errors are bugs, and stay exceptions (GW-04 section 5).
    private suspend fun <T> guarded(operation: suspend () -> StoreResult<T>): StoreResult<T> =
        try {
            // Mono.timeout, not withTimeoutOrNull: under Unconfined that resumes on DefaultExecutor, which BlockHound
            // does not watch (docs/knowledge/bugs/team-store-timeout-resumes-on-default-executor.md).
            mono(currentCoroutineContext().minusKey(Job)) { operation() }
                .timeout(timeout.toJavaDuration(), Mono.empty())
                .awaitSingleOrNull()
                ?: unavailable(TIMED_OUT, null)
        } catch (failure: TransientDataAccessException) {
            unavailable(failure.causeName, failure.sqlState)
        } catch (failure: NonTransientDataAccessResourceException) {
            unavailable(failure.causeName, failure.sqlState)
        }

    // The driver's message can quote the statement or the host, so the class and SQLSTATE stand in for it.
    private fun unavailable(
        cause: String,
        sqlState: String?,
    ): StoreResult.Failed {
        log.warn("Team store unavailable: cause={} sqlState={}", cause, sqlState)
        return StoreResult.Failed(TeamStoreFailure.Unavailable)
    }

    private val DataAccessException.driverFailure: R2dbcException?
        get() =
            generateSequence<Throwable>(this) {
                it.cause
            }.take(MAX_CAUSE_DEPTH).filterIsInstance<R2dbcException>().firstOrNull()

    private val DataAccessException.causeName: String get() = (driverFailure ?: this)::class.simpleName.orEmpty()

    private val DataAccessException.sqlState: String? get() = driverFailure?.sqlState

    private companion object {
        const val TEAM_COLUMNS = "id, name, created_at"
        const val KEY_COLUMNS = "id, team_id, name, hint, created_at, expires_at, revoked_at"
        const val FOREIGN_KEY_VIOLATION = "23503"
        const val TIMED_OUT = "deadline"

        // Spring nests its translation, and a cycle in a cause chain is possible.
        const val MAX_CAUSE_DEPTH = 8
        val log: Logger = LoggerFactory.getLogger(PostgresTeamStore::class.java)
    }
}
