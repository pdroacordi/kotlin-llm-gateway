package io.github.pdroacordi.llmgateway.gateway.app.security

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * A web page that rebinds its DNS name to 127.0.0.1 still sends its own `Host`, so a loopback bind alone does not
 * stop it (docs/knowledge/bugs/grafana-anonymous-admin-reachable-through-dns-rebinding.md). An absent `Host` passes: a
 * browser always sends one. Goes with GW-04.
 */
class LoopbackHostWebFilter(
    private val exposed: Boolean,
) : WebFilter,
    Ordered {
    // Right after RequestContextWebFilter, so the 403 has an x-request-id.
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 1

    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        val hosts = exchange.request.headers[HttpHeaders.HOST].orEmpty()
        return if (exposed || hosts.all { isLoopback(it) }) {
            chain.filter(exchange)
        } else {
            Mono.error(GatewayErrorException(GatewayError.RejectedRequest(FORBIDDEN)))
        }
    }

    private fun isLoopback(host: String): Boolean {
        val name = HOST.matchEntire(host)?.groupValues?.get(1)
        return name != null && ExposureGuard.isLoopback(name)
    }

    private companion object {
        const val FORBIDDEN = 403

        // `<name>` or `<name>:<digits>`; a bracketed name is an IPv6 literal.
        val HOST = Regex("""(\[[^\]]*:[^\]]*\]|[^:\[\]]*)(?::\d{1,5})?""")
    }
}
