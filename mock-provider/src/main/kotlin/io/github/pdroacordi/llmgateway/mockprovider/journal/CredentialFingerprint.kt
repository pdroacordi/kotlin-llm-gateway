package io.github.pdroacordi.llmgateway.mockprovider.journal

import java.security.MessageDigest
import java.util.Locale

enum class Scheme(
    val wire: String,
) {
    BEARER("bearer"),
    X_API_KEY("x-api-key"),
}

/** What the journal stores about a credential: never the value (ADR-FND-03-008). */
data class Credential(
    val scheme: Scheme,
    val fingerprint: String,
)

/** The credential a request presented. Held only while the request is handled; never stored or logged. */
data class PresentedCredential(
    val scheme: Scheme,
    val value: String,
) {
    override fun toString(): String = "PresentedCredential(scheme=$scheme)"

    companion object {
        private const val BEARER_PREFIX = "bearer "

        /** A non-empty `Authorization: Bearer` token first, then a non-empty `x-api-key`. */
        fun of(
            authorization: String?,
            apiKey: String?,
        ): PresentedCredential? {
            val bearer =
                authorization
                    ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
                    ?.substring(BEARER_PREFIX.length)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                bearer != null -> PresentedCredential(Scheme.BEARER, bearer)
                key != null -> PresentedCredential(Scheme.X_API_KEY, key)
                else -> null
            }
        }
    }
}

/** First 8 hex digits (32 bits) of SHA-256: a test computes it for its own fake key to see which key arrived. */
class CredentialFingerprint {
    // The JCA lookup happens here, at bean creation; requests clone the prototype instead (BlockHound, event loop).
    private val prototype: MessageDigest = MessageDigest.getInstance("SHA-256")

    fun of(value: String): String {
        val digest = (prototype.clone() as MessageDigest).digest(value.encodeToByteArray())
        return digest.take(PREFIX_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    fun of(credential: PresentedCredential): Credential = Credential(credential.scheme, of(credential.value))

    private companion object {
        const val PREFIX_BYTES = 4
    }
}
