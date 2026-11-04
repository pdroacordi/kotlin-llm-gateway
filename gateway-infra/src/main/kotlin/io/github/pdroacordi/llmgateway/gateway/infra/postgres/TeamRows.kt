package io.github.pdroacordi.llmgateway.gateway.infra.postgres

import io.github.pdroacordi.llmgateway.gateway.core.team.KeyDigest
import io.github.pdroacordi.llmgateway.gateway.core.team.Page
import io.github.pdroacordi.llmgateway.gateway.core.team.PageRequest
import io.github.pdroacordi.llmgateway.gateway.core.team.ResourceName
import io.github.pdroacordi.llmgateway.gateway.core.team.Team
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKey
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

// Between the domain types and the rows of V1__teams_and_keys.sql.

internal fun DatabaseClient.GenericExecuteSpec.bindPage(page: PageRequest): DatabaseClient.GenericExecuteSpec =
    bind("after", (page.after ?: Uuid.NIL).toJavaUuid()).bind("limit", page.limit + 1)

// The extra row sets hasMore.
internal fun <T> List<T>.toPage(page: PageRequest): Page<T> = Page(take(page.limit), size > page.limit)

internal fun KeyDigest.bytes(): ByteArray = HexFormat.of().parseHex(hex)

internal fun Readable.digest(): KeyDigest =
    KeyDigest(HexFormat.of().formatHex(required("digest", ByteArray::class.java)))

internal fun Readable.team(): Team = Team(TeamId(uuid("id")), name(), required("created_at", Instant::class.java))

internal fun Readable.teamKey(): TeamKey =
    TeamKey(
        TeamKeyId(uuid("id")),
        TeamId(uuid("team_id")),
        name(),
        required("hint", String::class.java),
        required("created_at", Instant::class.java),
        instantOrNull("expires_at"),
        instantOrNull("revoked_at"),
    )

internal fun Readable.name(): ResourceName =
    checkNotNull(ResourceName.parseOrNull(required("name", String::class.java))) { "A stored name is malformed" }

internal fun Readable.uuid(column: String): Uuid = required(column, UUID::class.java).toKotlinUuid()

internal fun Readable.instantOrNull(column: String): Instant? = get(column, Instant::class.java)

internal fun <T : Any> Readable.required(
    column: String,
    type: Class<T>,
): T = checkNotNull(get(column, type)) { "Column $column is null" }

// timestamptz keeps microseconds and rounds; truncating here makes the stored value the fake's.
internal fun Instant.stored(): Instant = truncatedTo(ChronoUnit.MICROS)
