package io.github.pdroacordi.llmgateway.gateway.app.auth

/** A secret from the environment. The public `String` constructor is what Boot binds (ADR-GW-04-008). */
class ConfiguredSecret(
    private val value: String,
) {
    val length: Int get() = value.length

    val isVisibleAscii: Boolean get() = value.all { it.code in VISIBLE_ASCII }

    fun reveal(): String = value

    override fun toString(): String = "[redacted]"

    private companion object {
        val VISIBLE_ASCII = 0x21..0x7E
    }
}
