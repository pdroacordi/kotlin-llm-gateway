package io.github.pdroacordi.llmgateway.gateway.app.auth

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.core.team.Authentication
import io.github.pdroacordi.llmgateway.gateway.core.team.OperatorAuthenticator
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamAuthenticator
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * Every request under `/v1` resolves to one team, and every request under `/admin` to the admin key, before routing
 * (ADR-GW-04-003). Scope is matched as the router matches, on the decoded path without `;` parameters: a `startsWith`
 * on the raw path would let `/v%31/chat/completions` through.
 */
class AuthWebFilter(
    private val teams: TeamAuthenticator,
    private val operator: OperatorAuthenticator,
) : CoWebFilter(),
    Ordered {
    // Right after RequestContextWebFilter, so a 401 and a 503 have an x-request-id.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        val path = exchange.request.path.pathWithinApplication()
        val authorization = exchange.request.headers[HttpHeaders.AUTHORIZATION].orEmpty()
        when {
            TEAM_SCOPE.matches(path) -> asTeam(authorization, exchange, chain)
            ADMIN_SCOPE.matches(path) -> asOperator(authorization, exchange, chain)
            else -> chain.filter(exchange)
        }
    }

    private suspend fun asTeam(
        authorization: List<String>,
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        when (val authentication = teams.authenticate(authorization)) {
            is Authentication.Rejected -> {
                throw GatewayErrorException(authentication.error)
            }

            is Authentication.Authenticated -> {
                val team = authentication.team
                val mdc = MDC.getCopyOfContextMap().orEmpty() + (GatewayHeaders.MDC_TEAM_ID to team.toString())
                withContext(TeamContext(team) + MDCContext(mdc)) {
                    chain.filter(exchange)
                }
            }
        }
    }

    private suspend fun asOperator(
        authorization: List<String>,
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        // Set first, so the rejections carry it too: one response of this API holds a key.
        exchange.response.headers.cacheControl = NO_STORE
        operator.check(authorization)?.let { throw GatewayErrorException(it) }
        chain.filter(exchange)
    }

    private companion object {
        const val NO_STORE = "no-store"
        val TEAM_SCOPE: PathPattern = PathPatternParser.defaultInstance.parse("/v1/**")
        val ADMIN_SCOPE: PathPattern = PathPatternParser.defaultInstance.parse("/admin/**")
    }
}
