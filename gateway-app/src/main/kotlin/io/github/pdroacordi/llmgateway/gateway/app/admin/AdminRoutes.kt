package io.github.pdroacordi.llmgateway.gateway.app.admin

import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.server.MethodNotAllowedException

/** A functional router answers a wrong method with 404, so each path ends with a 405 catch-all. */
object AdminRoutes {
    private const val TEAMS = "/admin/v1/teams"
    private const val KEYS = "$TEAMS/{teamId}/keys"
    private const val REVOKE = "$KEYS/{keyId}/revoke"

    fun routes(handler: AdminHandler): RouterFunction<ServerResponse> =
        coRouter {
            POST(TEAMS, handler::createTeam)
            GET(TEAMS, handler::listTeams)
            path(TEAMS) { request -> notAllowed(request, HttpMethod.GET, HttpMethod.POST) }
            POST(KEYS, handler::createKey)
            GET(KEYS, handler::listKeys)
            path(KEYS) { request -> notAllowed(request, HttpMethod.GET, HttpMethod.POST) }
            POST(REVOKE, handler::revokeKey)
            path(REVOKE) { request -> notAllowed(request, HttpMethod.POST) }
        }

    private fun notAllowed(
        request: ServerRequest,
        vararg allowed: HttpMethod,
    ): ServerResponse = throw MethodNotAllowedException(request.method(), allowed.toSet())
}
