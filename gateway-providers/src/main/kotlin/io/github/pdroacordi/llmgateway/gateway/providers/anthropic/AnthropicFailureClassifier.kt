package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.RetryAfter
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamRefusals
import org.springframework.http.HttpHeaders
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import java.time.Instant

/**
 * A provider's status, headers and body to a [ProviderFailure]: the two tables of GW-03 section 4 (ADR-GW-03-013). The
 * provider's `message` is never read (ADR-GW-01-005).
 */
internal class AnthropicFailureClassifier(
    private val trees: ObjectReader,
) : StreamRefusals {
    override fun classify(
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        now: Instant,
    ): ProviderFailure {
        val fields = errorFields(body)
        // First: the docs say it can arrive with other statuses, such as a 500.
        if (fields.type == TIMEOUT_TYPE) return ProviderFailure.UpstreamTimeout
        return when (status) {
            BAD_REQUEST, CONTENT_TOO_LARGE, UNPROCESSABLE_CONTENT -> {
                ProviderFailure.RejectedByProvider(status, null, null)
            }

            UNAUTHORIZED, FORBIDDEN -> {
                ProviderFailure.UpstreamAuthFailed
            }

            PAYMENT_REQUIRED -> {
                ProviderFailure.UpstreamQuotaExhausted
            }

            NOT_FOUND -> {
                if (fields.type == NOT_FOUND_TYPE) ProviderFailure.ModelNotFound else ProviderFailure.UpstreamError
            }

            REQUEST_TIMEOUT, GATEWAY_TIMEOUT -> {
                ProviderFailure.UpstreamTimeout
            }

            TOO_MANY_REQUESTS -> {
                if (fields.code == SPEND_LIMIT) {
                    ProviderFailure.UpstreamQuotaExhausted
                } else {
                    ProviderFailure.UpstreamRateLimited(RetryAfter.parse(headers, now))
                }
            }

            SERVICE_UNAVAILABLE, OVERLOADED -> {
                ProviderFailure.ProviderUnavailable(RetryAfter.parse(headers, now))
            }

            else -> {
                ProviderFailure.UpstreamError
            }
        }
    }

    /** Tolerates a non-JSON body, such as a proxy's HTML 502. */
    override fun errorFields(body: ByteArray): ProviderErrorFields =
        trees.readTreeOrNull(body)?.get("error")?.let(::errorFields) ?: ProviderErrorFields.NONE

    /** [error] is the provider's `error` member. */
    fun errorFields(error: JsonNode): ProviderErrorFields =
        ProviderErrorFields.of(error, error.get("details")?.get("error_code"))

    /** No status here, so `error.type` decides; the table mirrors [classify] row for row. */
    fun classifyInBand(fields: ProviderErrorFields): ProviderFailure =
        when (fields.type) {
            "overloaded_error" -> {
                ProviderFailure.ProviderUnavailable(null)
            }

            "rate_limit_error" -> {
                if (fields.code == SPEND_LIMIT) {
                    ProviderFailure.UpstreamQuotaExhausted
                } else {
                    ProviderFailure.UpstreamRateLimited(null)
                }
            }

            "billing_error" -> {
                ProviderFailure.UpstreamQuotaExhausted
            }

            "authentication_error", "permission_error" -> {
                ProviderFailure.UpstreamAuthFailed
            }

            "invalid_request_error" -> {
                ProviderFailure.RejectedByProvider(BAD_REQUEST, null, null)
            }

            "request_too_large" -> {
                ProviderFailure.RejectedByProvider(CONTENT_TOO_LARGE, null, null)
            }

            NOT_FOUND_TYPE -> {
                ProviderFailure.ModelNotFound
            }

            TIMEOUT_TYPE -> {
                ProviderFailure.UpstreamTimeout
            }

            else -> {
                ProviderFailure.UpstreamError
            }
        }

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val PAYMENT_REQUIRED = 402
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val REQUEST_TIMEOUT = 408
        const val CONTENT_TOO_LARGE = 413
        const val UNPROCESSABLE_CONTENT = 422
        const val TOO_MANY_REQUESTS = 429
        const val SERVICE_UNAVAILABLE = 503
        const val GATEWAY_TIMEOUT = 504
        const val OVERLOADED = 529
        const val NOT_FOUND_TYPE = "not_found_error"
        const val TIMEOUT_TYPE = "timeout_error"
        const val SPEND_LIMIT = "enforced_spend_limit_reached"
    }
}
