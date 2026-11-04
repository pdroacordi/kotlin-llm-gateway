package io.github.pdroacordi.llmgateway.gateway.app.admin

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.PageRequest
import io.github.pdroacordi.llmgateway.gateway.core.team.ResourceName
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamKeyId
import org.springframework.web.reactive.function.server.ServerRequest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.jvm.optionals.getOrNull
import kotlin.uuid.Uuid

/** The validation table of GW-04 section 4. No failure quotes a value. */
internal object AdminRequestReader {
    data class NewKey(
        val name: ResourceName,
        val expiresAt: Instant?,
    )

    private const val NAME = "name"
    private const val EXPIRES_AT = "expires_at"

    // ISO_OFFSET_DATE_TIME alone also takes a time with no seconds, which RFC 3339 does not.
    private val RFC_3339 = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})""")

    fun teamName(body: ObjectNode): ResourceName {
        refuseOtherMembers(body, setOf(NAME))
        return name(body)
    }

    fun newKey(body: ObjectNode): NewKey {
        refuseOtherMembers(body, setOf(NAME, EXPIRES_AT))
        return NewKey(name(body), expiresAt(body.get(EXPIRES_AT)))
    }

    fun page(request: ServerRequest): PageRequest {
        val limit = request.queryParam("limit").getOrNull()?.let(::limit) ?: PageRequest.DEFAULT_LIMIT
        val after =
            request.queryParam("after").getOrNull()?.let {
                Uuid.parseOrNull(it) ?: throw GatewayErrorException(GatewayError.InvalidRequest.afterInvalid)
            }
        return PageRequest(limit, after)
    }

    // A path value that is not a UUID cannot exist, so it is that resource's 404.
    fun teamId(
        request: ServerRequest,
        absent: TeamStoreFailure = TeamStoreFailure.TeamNotFound,
    ): TeamId = TeamId(Uuid.parseOrNull(request.pathVariable("teamId")) ?: throw GatewayErrorException(absent))

    fun keyId(request: ServerRequest): TeamKeyId =
        TeamKeyId(
            Uuid.parseOrNull(request.pathVariable("keyId"))
                ?: throw GatewayErrorException(TeamStoreFailure.KeyNotFound),
        )

    // A typo of expires_at would otherwise create a key that never expires (ADR-GW-04-007).
    private fun refuseOtherMembers(
        body: ObjectNode,
        allowed: Set<String>,
    ) {
        if (!allowed.containsAll(body.propertyNames())) {
            throw GatewayErrorException(GatewayError.InvalidRequest.unknownMember)
        }
    }

    private fun name(body: ObjectNode): ResourceName {
        val node = body.get(NAME)
        val name = if (node != null && node.isString) ResourceName.parseOrNull(node.stringValue()) else null
        return name ?: throw GatewayErrorException(GatewayError.InvalidRequest.nameInvalid)
    }

    private fun expiresAt(node: JsonNode?): Instant? {
        if (node == null || node.isNull) return null
        val text = node.takeIf { it.isString }?.stringValue()?.takeIf { RFC_3339.matches(it) }
        return text?.let(::instantOrNull) ?: throw GatewayErrorException(GatewayError.InvalidRequest.expiresAtInvalid)
    }

    private fun instantOrNull(text: String): Instant? =
        try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun limit(text: String): Int =
        text.toIntOrNull()?.takeIf { it in 1..PageRequest.MAX_LIMIT }
            ?: throw GatewayErrorException(GatewayError.InvalidRequest.limitInvalid)
}
