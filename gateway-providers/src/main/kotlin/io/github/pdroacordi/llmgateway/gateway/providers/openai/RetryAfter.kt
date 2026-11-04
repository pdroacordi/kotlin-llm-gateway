package io.github.pdroacordi.llmgateway.gateway.providers.openai

import org.springframework.http.HttpHeaders
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** `retry-after-ms`, else `retry-after` as seconds, else as an HTTP-date; null when none gives a wait. */
internal object RetryAfter {
    private const val MILLISECONDS_HEADER = "retry-after-ms"
    private const val HEADER = "retry-after"

    fun parse(
        headers: HttpHeaders,
        now: Instant,
    ): Duration? {
        val milliseconds = headers.getFirst(MILLISECONDS_HEADER)?.toPositiveDouble()?.milliseconds
        val value = headers.getFirst(HEADER)
        val wait =
            milliseconds
                ?: value?.toPositiveDouble()?.seconds
                ?: value?.let { untilDate(it, now) }
        return wait?.takeIf { it.isPositive() && it.isFinite() }
    }

    // openai-python reads both headers as floats.
    private fun String.toPositiveDouble(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    private fun untilDate(
        value: String,
        now: Instant,
    ): Duration? =
        try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            java.time.Duration
                .between(now, date)
                .toKotlinDuration()
        } catch (_: DateTimeParseException) {
            null
        }
}
