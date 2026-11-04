package io.github.pdroacordi.llmgateway.gateway.providers

/** A provider credential. The public `String` constructor is what Boot binds (ADR-GW-01-012). */
class ApiKey(
    private val value: String,
) {
    val isBlank: Boolean get() = value.isBlank()

    /** False for what a header cannot carry, such as the newline a secret file written with `echo` ends in. */
    val isVisibleAscii: Boolean get() = value.all { it.code in VISIBLE_ASCII }

    fun reveal(): String = value

    override fun toString(): String = "[redacted]"

    private companion object {
        val VISIBLE_ASCII = 0x21..0x7E
    }
}
