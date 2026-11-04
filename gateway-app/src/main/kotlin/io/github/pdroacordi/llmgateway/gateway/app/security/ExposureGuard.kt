package io.github.pdroacordi.llmgateway.gateway.app.security

import org.slf4j.LoggerFactory
import java.net.InetAddress

/** Until GW-04 anyone who reaches the port spends the provider key, so the app stays on loopback (ADR-GW-01-007). */
object ExposureGuard {
    private const val LOCALHOST = "localhost"
    private const val FLAG = "llmgw.security.allow-unauthenticated-exposure"
    private val IP_LITERAL = Regex("""[0-9a-fA-F:.]+""")
    private val log = LoggerFactory.getLogger(ExposureGuard::class.java)

    /** [address] is `server.address`; null means every interface. */
    fun check(
        address: String?,
        allowExposure: Boolean,
    ) {
        if (allowExposure) {
            log.warn("{}=true: no authentication and no Host check; anyone who reaches this port spends the key.", FLAG)
            return
        }
        check(address != null && isLoopback(address)) {
            "server.address is not a loopback address and this gateway has no authentication yet; " +
                "bind it to 127.0.0.1 or set $FLAG=true."
        }
    }

    /** `127.0.0.0/8`, `::1` or `localhost`. Never a name lookup, so a Host header cannot trigger one. */
    fun isLoopback(host: String): Boolean {
        val literal = host.removeSurrounding("[", "]")
        return literal.equals(LOCALHOST, ignoreCase = true) || isLoopbackLiteral(literal)
    }

    private fun isLoopbackLiteral(literal: String): Boolean =
        IP_LITERAL.matches(literal) &&
            try {
                InetAddress.ofLiteral(literal).isLoopbackAddress
            } catch (_: IllegalArgumentException) {
                false
            }
}
