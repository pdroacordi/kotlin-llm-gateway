package io.github.pdroacordi.llmgateway.gateway.core.team

import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Lowercase hex of the 32-byte HMAC. */
@JvmInline
value class KeyDigest(
    val hex: String,
) {
    override fun toString(): String = "[digest]"
}

/** A team key in plain form; only [reveal] shows it. Not `ApiKey`, which is the provider credential. */
class TeamKeySecret(
    private val value: String,
) {
    fun reveal(): String = value

    override fun toString(): String = "[redacted]"
}

/**
 * HMAC-SHA-256 with a secret from the environment (ADR-GW-04-002). `Mac` is not thread-safe and `Mac.getInstance` would
 * run a provider lookup on an event loop, so each digest clones one initialised prototype (ADR-GW-04-012).
 */
class KeyHasher(
    secret: ByteArray,
    val version: Short = 1,
) {
    private val prototype: Mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret, ALGORITHM)) }

    init {
        // A JCE provider without clone support fails here, at startup, and not on the first request.
        digest("")
    }

    fun digest(token: String): KeyDigest {
        val mac = prototype.clone() as Mac
        return KeyDigest(HexFormat.of().formatHex(mac.doFinal(token.encodeToByteArray())))
    }

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
