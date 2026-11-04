package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import java.time.Instant

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

        /** [error] is the provider's `error` member; anything but an object has no fields. */
        fun of(error: JsonNode): ProviderErrorFields {
            if (!error.isObject) return NONE
            return ProviderErrorFields(
                code = error.safeText("code", CODE),
                type = error.safeText("type", CODE),
                param = error.safeText("param", PARAM),
            )
        }

        private fun JsonNode.safeText(
            name: String,
            allowed: Regex,
        ): String? =
            get(name)
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { allowed.matches(it) }
    }
}

/**
 * A provider's status, headers and body to a [ProviderFailure]: the upstream table of GW-01 section 4. The provider's
 * `message` is never read; it can name the organization or a masked key (ADR-GW-01-005).
 */
internal class OpenAiFailureClassifier(
    private val trees: ObjectReader,
) {
    fun classify(
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        now: Instant,
    ): ProviderFailure {
        val fields = errorFields(body)
        return when (status) {
            NOT_FOUND -> {
                if (fields.code == MODEL_NOT_FOUND) ProviderFailure.ModelNotFound else ProviderFailure.UpstreamError
            }

            BAD_REQUEST, CONTENT_TOO_LARGE, UNPROCESSABLE_CONTENT -> {
                ProviderFailure.RejectedByProvider(status, fields.code, fields.param)
            }

            UNAUTHORIZED, FORBIDDEN -> {
                ProviderFailure.UpstreamAuthFailed
            }

            TOO_MANY_REQUESTS -> {
                if (fields.code in QUOTA_SET || fields.type in QUOTA_SET) {
                    ProviderFailure.UpstreamQuotaExhausted
                } else {
                    ProviderFailure.UpstreamRateLimited(RetryAfter.parse(headers, now))
                }
            }

            SERVICE_UNAVAILABLE -> {
                ProviderFailure.ProviderUnavailable(RetryAfter.parse(headers, now))
            }

            REQUEST_TIMEOUT, GATEWAY_TIMEOUT -> {
                ProviderFailure.UpstreamTimeout
            }

            else -> {
                ProviderFailure.UpstreamError
            }
        }
    }

    /** Tolerates a non-JSON body, such as a proxy's HTML 502. */
    fun errorFields(body: ByteArray): ProviderErrorFields =
        trees.readTreeOrNull(body)?.get("error")?.let(ProviderErrorFields::of) ?: ProviderErrorFields.NONE

    /** GW-02 section 4. OpenAI documents no in-band shape, so this table is the gateway's reading of the SDKs. */
    fun classifyInBand(fields: ProviderErrorFields): ProviderFailure =
        when {
            fields.code in QUOTA_SET || fields.type in QUOTA_SET -> {
                ProviderFailure.UpstreamQuotaExhausted
            }

            fields.code == MODEL_NOT_FOUND -> {
                ProviderFailure.ModelNotFound
            }

            fields.type == RATE_LIMIT_TYPE || fields.code == RATE_LIMIT_CODE -> {
                ProviderFailure.UpstreamRateLimited(null)
            }

            fields.type == INVALID_REQUEST_TYPE -> {
                ProviderFailure.RejectedByProvider(BAD_REQUEST, fields.code, fields.param)
            }

            else -> {
                ProviderFailure.UpstreamError
            }
        }

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val REQUEST_TIMEOUT = 408
        const val CONTENT_TOO_LARGE = 413
        const val UNPROCESSABLE_CONTENT = 422
        const val TOO_MANY_REQUESTS = 429
        const val SERVICE_UNAVAILABLE = 503
        const val GATEWAY_TIMEOUT = 504
        const val MODEL_NOT_FOUND = "model_not_found"
        const val RATE_LIMIT_TYPE = "rate_limit_error"
        const val RATE_LIMIT_CODE = "rate_limit_exceeded"
        const val INVALID_REQUEST_TYPE = "invalid_request_error"

        // OpenAI's error-codes guide; insufficient_quota from FND-03's override note.
        val QUOTA_SET =
            setOf(
                "insufficient_quota",
                "credit_balance_exhausted",
                "organization_spend_limit_exceeded",
                "project_spend_limit_exceeded",
                "organization_usage_limit_exceeded",
            )
    }
}
