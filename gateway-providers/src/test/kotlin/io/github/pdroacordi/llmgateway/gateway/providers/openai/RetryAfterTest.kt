package io.github.pdroacordi.llmgateway.gateway.providers.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpHeaders
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryAfterTest {
    private fun headers(vararg pairs: Pair<String, String>) =
        HttpHeaders().apply { pairs.forEach { add(it.first, it.second) } }

    @Test
    fun `retry-after-ms wins over retry-after`() {
        val headers = headers("retry-after-ms" to "1500", "retry-after" to "9")

        assertEquals(1500.milliseconds, RetryAfter.parse(headers, NOW))
    }

    @Test
    fun `retry-after is read as seconds`() {
        assertEquals(7.seconds, RetryAfter.parse(headers("retry-after" to "7"), NOW))
    }

    @Test
    fun `a fractional value is kept, as openai-python reads it`() {
        assertEquals(1500.milliseconds, RetryAfter.parse(headers("retry-after" to "1.5"), NOW))
    }

    @Test
    fun `retry-after as an HTTP-date is measured against the clock`() {
        val headers = headers("retry-after" to "Tue, 03 Nov 2026 12:00:30 GMT")

        assertEquals(30.seconds, RetryAfter.parse(headers, NOW))
    }

    @Test
    fun `an HTTP-date in the past gives null`() {
        assertNull(RetryAfter.parse(headers("retry-after" to "Tue, 03 Nov 2026 11:59:30 GMT"), NOW))
    }

    @Test
    fun `no header gives null`() {
        assertNull(RetryAfter.parse(HttpHeaders(), NOW))
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "soon", "", "NaN", "Infinity", "1e400"])
    fun `zero, negative and garbage give null`(value: String) {
        assertNull(RetryAfter.parse(headers("retry-after" to value), NOW))
        assertNull(RetryAfter.parse(headers("retry-after-ms" to value), NOW))
    }

    @Test
    fun `an unusable retry-after-ms falls back to retry-after`() {
        val headers = headers("retry-after-ms" to "soon", "retry-after" to "2")

        assertEquals(2.seconds, RetryAfter.parse(headers, NOW))
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-03T12:00:00Z")
    }
}
