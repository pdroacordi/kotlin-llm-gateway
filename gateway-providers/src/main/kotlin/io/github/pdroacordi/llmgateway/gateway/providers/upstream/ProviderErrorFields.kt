package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import tools.jackson.databind.JsonNode

/** The machine-readable members of a provider error, each null unless it passed the character check. */
internal data class ProviderErrorFields(
    val code: String?,
    val type: String?,
    val param: String?,
) {
    companion object {
        val NONE = ProviderErrorFields(null, null, null)

        // Tight enough that neither can carry a sentence (GW-01 section 4).
        private val CODE = Regex("""[a-z0-9_.-]{1,64}""")
        private val PARAM = Regex("""[A-Za-z0-9_.\[\]-]{1,128}""")

        /** [value] if it can go in a `param` member or a log line; it may come from a client. */
        fun safeParam(value: String): String? = value.takeIf { PARAM.matches(it) }

        /** [value] if it can go in a log line; it comes from a provider. */
        fun safeCode(value: String): String? = value.takeIf { CODE.matches(it) }

        /**
         * [error] is the provider's `error` member; anything but an object has no fields. [code] is where the provider
         * keeps its code, checked like the rest.
         */
        fun of(
            error: JsonNode,
            code: JsonNode? = error.get("code"),
        ): ProviderErrorFields {
            if (!error.isObject) return NONE
            return ProviderErrorFields(
                code = code.safeText(CODE),
                type = error.get("type").safeText(CODE),
                param = error.get("param").safeText(PARAM),
            )
        }

        private fun JsonNode?.safeText(allowed: Regex): String? =
            this
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { allowed.matches(it) }
    }
}
