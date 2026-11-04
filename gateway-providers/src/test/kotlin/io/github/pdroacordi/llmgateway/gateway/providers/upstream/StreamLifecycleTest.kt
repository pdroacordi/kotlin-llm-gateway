package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamLifecycleTest {
    private val failed = ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)

    /** Data `open`, `drain` or `end` picks the state; anything else leaves it open with no event. */
    private class FakeDecoder(
        private val atEnd: List<ProviderStreamEvent>,
    ) : StreamDecoder {
        var decoded = 0
        override val errorFields: ProviderErrorFields? = ProviderErrorFields("c", "t", null)

        override fun decode(event: SseEvent): Decoded {
            decoded++
            return when (event.data.decodeToString()) {
                "drain" -> Decoded.Draining(listOf(ProviderStreamEvent.Completed))
                "end" -> Decoded.Ended(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)))
                else -> Decoded.Open(emptyList())
            }
        }

        override fun atEnd(): List<ProviderStreamEvent> = atEnd
    }

    private val decoder = FakeDecoder(listOf(ProviderStreamEvent.Completed))
    private val lifecycle = StreamLifecycle(decoder, LIMIT)

    private fun feed(vararg data: String): List<ProviderStreamEvent> =
        lifecycle.feed(data.joinToString("") { "data: $it\n\n" }.encodeToByteArray())

    @Test
    fun `an open event keeps the stream open`() {
        assertTrue(feed("x").isEmpty())
        assertFalse(lifecycle.ended)
        assertFalse(lifecycle.draining)
    }

    @Test
    fun `draining ignores the rest of the feed and later feeds`() {
        assertEquals(listOf(ProviderStreamEvent.Completed), feed("drain", "x"))
        assertEquals(1, decoder.decoded)
        assertTrue(lifecycle.draining)
        assertTrue(feed("x").isEmpty())
    }

    @Test
    fun `ended ignores the rest of the feed`() {
        feed("end", "x")

        assertEquals(1, decoder.decoded)
        assertTrue(lifecycle.ended)
    }

    @Test
    fun `an event over the limit fails the stream`() {
        val events = lifecycle.feed("data: ${"x".repeat(LIMIT + 1)}\n\n".encodeToByteArray())

        assertEquals(listOf(failed), events)
        assertTrue(lifecycle.ended)
    }

    @Test
    fun `an overflow after an ending event adds nothing`() {
        val bytes = "data: end\n\ndata: ${"x".repeat(LIMIT + 1)}\n\n".encodeToByteArray()

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)), lifecycle.feed(bytes))
    }

    @Test
    fun `finish in the open state asks the decoder and ends`() {
        assertEquals(listOf(ProviderStreamEvent.Completed), lifecycle.finish())
        assertTrue(lifecycle.ended)
    }

    @Test
    fun `finish after the terminal marker gives nothing`() {
        feed("drain")

        assertTrue(lifecycle.finish().isEmpty())
    }

    @Test
    fun `a terminal the collector never got wins over a late failure, once`() {
        feed("drain")

        assertEquals(listOf(ProviderStreamEvent.Completed), lifecycle.fail(ProviderFailure.UpstreamTimeout))
        assertTrue(lifecycle.fail(ProviderFailure.UpstreamTimeout).isEmpty())
    }

    @Test
    fun `after the collector has the terminal a late failure gives nothing`() {
        feed("drain")
        lifecycle.delivered()

        assertTrue(lifecycle.fail(ProviderFailure.UpstreamTimeout).isEmpty())
    }

    @Test
    fun `a failure in the open state ends the stream`() {
        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)),
            lifecycle.fail(ProviderFailure.UpstreamTimeout),
        )
        assertTrue(lifecycle.ended)
        assertTrue(feed("x").isEmpty())
    }

    @Test
    fun `the error fields are the decoder's`() {
        assertEquals("c", lifecycle.errorFields?.code)
    }

    private companion object {
        const val LIMIT = 64
    }
}
