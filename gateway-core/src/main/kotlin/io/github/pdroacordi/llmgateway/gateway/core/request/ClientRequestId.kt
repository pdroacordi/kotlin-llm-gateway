package io.github.pdroacordi.llmgateway.gateway.core.request

/** A client's own correlation ID from `x-client-request-id`, echoed and logged (ADR-FND-04-001). */
@JvmInline
value class ClientRequestId private constructor(
    val value: String,
) {
    companion object {
        /** OpenAI's limit for `X-Client-Request-Id`. */
        const val MAX_LENGTH = 512

        /** Accepts 1 to [MAX_LENGTH] characters, each printable ASCII (U+0020 to U+007E). */
        fun parseOrNull(raw: String): ClientRequestId? =
            if (raw.length in 1..MAX_LENGTH && raw.all { it in ' '..'~' }) ClientRequestId(raw) else null
    }
}
