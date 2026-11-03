package io.github.pdroacordi.llmgateway.mockprovider.journal

/**
 * Header values kept in the journal. Redaction by name pattern rather than an allowlist, so `traceparent` and
 * `x-client-request-id` stay usable and new secret-looking names are caught (ADR-FND-03-008).
 */
object HeaderRedaction {
    const val REDACTED = "[redacted]"

    private val SECRET_NAMES =
        setOf("authorization", "proxy-authorization", "x-api-key", "api-key", "cookie", "set-cookie")
    private val SECRET_PARTS = listOf("auth", "key", "token", "secret", "password", "cookie")

    fun isSecret(name: String): Boolean {
        val lower = name.lowercase()
        return lower in SECRET_NAMES || SECRET_PARTS.any { it in lower }
    }

    /** Names lowercased; every value of a secret-looking name becomes [REDACTED]. */
    fun redact(headers: Map<String, List<String>>): Map<String, List<String>> =
        headers.entries.associate { (name, values) ->
            name.lowercase() to if (isSecret(name)) values.map { REDACTED } else values.toList()
        }
}
