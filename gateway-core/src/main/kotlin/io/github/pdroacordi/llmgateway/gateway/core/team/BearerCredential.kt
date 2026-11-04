package io.github.pdroacordi.llmgateway.gateway.core.team

/** What the `Authorization` lines of a request hold, before any check of the token (GW-04 section 4). */
sealed interface BearerCredential {
    /** No header, another scheme or an empty token. */
    data object Missing : BearerCredential

    /** More than one line: never pick one (ADR-GW-04-017). */
    data object Ambiguous : BearerCredential

    data class Token(
        val value: String,
    ) : BearerCredential {
        override fun toString(): String = "Token([redacted])"
    }

    companion object {
        private const val SCHEME = "Bearer"

        /** [authorization] is every value of the header. */
        fun parse(authorization: List<String>): BearerCredential =
            when (authorization.size) {
                0 -> Missing
                1 -> parseLine(authorization.single())
                else -> Ambiguous
            }

        private fun parseLine(line: String): BearerCredential {
            val scheme = line.substringBefore(' ')
            val token = line.substringAfter(' ', "").trim(' ')
            return if (scheme.equals(SCHEME, ignoreCase = true) && token.isNotEmpty()) Token(token) else Missing
        }
    }
}
