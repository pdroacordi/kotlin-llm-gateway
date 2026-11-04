package io.github.pdroacordi.llmgateway.gateway.app.admin

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.web.readJsonObject
import io.github.pdroacordi.llmgateway.gateway.core.error.TeamStoreFailure
import io.github.pdroacordi.llmgateway.gateway.core.team.StoreResult
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAdminService
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper

/** The five routes of `/admin/v1`. Authentication is `AuthWebFilter`'s; the audit lines hold IDs only. */
class AdminHandler(
    private val service: TeamAdminService,
    mapper: JsonMapper,
) {
    private val trees = mapper.exactTreeReader()
    private val writer = AdminResponseWriter(mapper)

    suspend fun createTeam(request: ServerRequest): ServerResponse {
        val name = AdminRequestReader.teamName(request.readJsonObject(trees))
        val team = service.createTeam(name).orThrow()
        log.info("Team created: team={}", team.id)
        return writer.created(writer.team(team))
    }

    suspend fun listTeams(request: ServerRequest): ServerResponse {
        val page = service.listTeams(AdminRequestReader.page(request)).orThrow()
        return writer.ok(writer.list(page, { it.id.toString() }, writer::team))
    }

    suspend fun createKey(request: ServerRequest): ServerResponse {
        val team = AdminRequestReader.teamId(request)
        val new = AdminRequestReader.newKey(request.readJsonObject(trees))
        val created = service.createKey(team, new.name, new.expiresAt).orThrow()
        log.info("Key created: team={} key={}", team, created.key.id)
        return writer.created(writer.createdKey(created))
    }

    suspend fun listKeys(request: ServerRequest): ServerResponse {
        val team = AdminRequestReader.teamId(request)
        val page = service.listKeys(team, AdminRequestReader.page(request)).orThrow()
        return writer.ok(writer.list(page, { it.id.toString() }, writer::key))
    }

    suspend fun revokeKey(request: ServerRequest): ServerResponse {
        // Revoke's only 404 is key_not_found, which a valid but unknown team also gets (GW-04 section 4).
        val team = AdminRequestReader.teamId(request, TeamStoreFailure.KeyNotFound)
        val key = AdminRequestReader.keyId(request)
        val revoked = service.revokeKey(team, key).orThrow()
        log.info("Key revoked: team={} key={}", team, key)
        return writer.ok(writer.key(revoked))
    }

    private fun <T> StoreResult<T>.orThrow(): T =
        when (this) {
            is StoreResult.Ok -> value
            is StoreResult.Failed -> throw GatewayErrorException(error)
        }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(AdminHandler::class.java)
    }
}
