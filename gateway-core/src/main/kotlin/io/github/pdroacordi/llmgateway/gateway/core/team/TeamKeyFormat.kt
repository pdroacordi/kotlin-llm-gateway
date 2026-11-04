package io.github.pdroacordi.llmgateway.gateway.core.team

import java.util.Base64
import kotlin.random.Random

/** `llmgw_` plus 43 characters of unpadded base64url, encoding 32 bytes; no checksum (ADR-GW-04-002). */
internal object TeamKeyFormat {
    private const val PREFIX = "llmgw_"
    private const val SECRET_BYTES = 32
    private const val HINT_SECRET_CHARACTERS = 4
    private val FORMAT = Regex("llmgw_[A-Za-z0-9_-]{43}")
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()

    fun matches(token: String): Boolean = FORMAT.matches(token)

    fun generate(secrets: Random): String = PREFIX + ENCODER.encodeToString(secrets.nextBytes(SECRET_BYTES))

    /** 24 of the 256 bits. */
    fun hint(token: String): String = token.take(PREFIX.length + HINT_SECRET_CHARACTERS)
}
