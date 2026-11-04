package io.github.pdroacordi.llmgateway.gateway.app.auth

import java.net.InetAddress

/** `local`'s fake secrets are public, so they must never guard a port past loopback (ADR-GW-04-019). */
internal object PublishedSecrets {
    // Must match application-local.yaml; PublishedSecretsTest reads the file.
    const val HMAC_SECRET = "local-profile-hmac-secret-not-a-secret"
    const val ADMIN_KEY = "local-profile-admin-key-not-a-secret"

    private const val LOCALHOST = "localhost"
    private val IP_LITERAL = Regex("""[0-9a-fA-F:.]+""")

    /** [address] is `server.address`; null means every interface. Names properties only (ADR-GW-04-008). */
    fun check(
        properties: AuthProperties,
        address: String?,
    ) {
        if (address != null && isLoopback(address)) return
        check(properties.hmacSecret.reveal() != HMAC_SECRET) { refusal("llmgw.auth.hmac-secret") }
        check(properties.adminKey.reveal() != ADMIN_KEY) { refusal("llmgw.auth.admin-key") }
    }

    private fun refusal(property: String): String =
        "$property holds the published value of the local profile and server.address is not loopback: " +
            "set your own value, or bind to 127.0.0.1."

    /** `127.0.0.0/8`, `::1` or `localhost`. Never a name lookup. */
    private fun isLoopback(address: String): Boolean {
        val literal = address.removeSurrounding("[", "]")
        return literal.equals(LOCALHOST, ignoreCase = true) ||
            (IP_LITERAL.matches(literal) && isLoopbackLiteral(literal))
    }

    private fun isLoopbackLiteral(literal: String): Boolean =
        try {
            InetAddress.ofLiteral(literal).isLoopbackAddress
        } catch (_: IllegalArgumentException) {
            false
        }
}
