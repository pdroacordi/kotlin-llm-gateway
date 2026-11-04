package io.github.pdroacordi.llmgateway.gateway.core.team

/** A team or key name: safe in logs and CLI arguments (ADR-GW-04-007). */
@JvmInline
value class ResourceName private constructor(
    val value: String,
) {
    override fun toString(): String = value

    companion object {
        private val FORMAT = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        fun parseOrNull(text: String): ResourceName? = if (FORMAT.matches(text)) ResourceName(text) else null
    }
}
