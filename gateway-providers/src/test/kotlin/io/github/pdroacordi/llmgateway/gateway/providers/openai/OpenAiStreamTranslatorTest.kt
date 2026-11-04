package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OpenAiStreamTranslatorTest {
    private val trees = JsonMapper.builder().build().exactTreeReader()
    private val translator = OpenAiStreamTranslator(OpenAiChunkReader(trees), OpenAiFailureClassifier(trees), LIMIT)

    private fun feed(text: String): List<ProviderStreamEvent> = translator.feed(text.encodeToByteArray())

    private fun event(json: String): String = "data: $json\n\n"

    private fun failed(failure: ProviderFailure) = listOf(ProviderStreamEvent.Failed(failure))

    private fun assertEndsWithNothingMore() {
        translator.delivered()
        assertTrue(feed(event(CONTENT)).isEmpty())
        assertTrue(translator.finish().isEmpty())
        assertTrue(translator.fail(ProviderFailure.UpstreamTimeout).isEmpty())
    }

    @Test
    fun `Open, a chunk gives Chunk`() {
        val events = feed(event(CONTENT))

        assertEquals(
            "Hi",
            (events.single() as ProviderStreamEvent.Chunk)
                .chunk.choices
                .single()
                .delta.content,
        )
        assertFalse(translator.ended)
        assertFalse(translator.draining)
    }

    @ParameterizedTest
    @ValueSource(strings = ["[DONE]", "[DONE] ", "[DONE]x"])
    fun `Open, a data starting with DONE gives Completed and drains`(done: String) {
        val events = feed(event(done))

        assertEquals(listOf(ProviderStreamEvent.Completed), events)
        assertTrue(translator.draining)
        assertFalse(translator.ended)
    }

    @Test
    fun `Draining, anything gives nothing`() {
        feed(event("[DONE]"))

        assertEndsWithNothingMore()
        assertTrue(translator.draining)
    }

    @Test
    fun `Open, an in-band error gives the classified failure and ends`() {
        val events = feed(event("""{"error":{"message":"m","type":"rate_limit_error","param":null,"code":null}}"""))

        assertEquals(failed(ProviderFailure.UpstreamRateLimited(null)), events)
        assertTrue(translator.ended)
        assertEquals("rate_limit_error", translator.errorFields?.type)
        assertEndsWithNothingMore()
    }

    @Test
    fun `Open, unreadable data gives UpstreamError and ends`() {
        assertEquals(failed(ProviderFailure.UpstreamError), feed(event("{not json")))
        assertTrue(translator.ended)
        assertNull(translator.errorFields)
        assertEndsWithNothingMore()
    }

    @Test
    fun `Open, an event over the limit gives UpstreamError and ends`() {
        val events = feed("data: ${"x".repeat(LIMIT)}\n\n")

        assertEquals(failed(ProviderFailure.UpstreamError), events)
        assertTrue(translator.ended)
        assertEndsWithNothingMore()
    }

    @Test
    fun `chunks before an event over the limit are kept, then UpstreamError`() {
        val events = feed(event(CONTENT) + "data: ${"x".repeat(LIMIT)}")

        assertEquals(2, events.size)
        assertTrue(events[0] is ProviderStreamEvent.Chunk)
        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events[1])
    }

    @Test
    fun `Open, an event with no data, a comment and an empty data line give nothing`() {
        assertTrue(feed(": ping\n\nevent: x\n\ndata:\n\n\n").isEmpty())
        assertFalse(translator.ended)
    }

    @Test
    fun `Open, finish after a finish_reason gives Completed`() {
        feed(event(FINISH))

        assertEquals(listOf(ProviderStreamEvent.Completed), translator.finish())
        assertTrue(translator.ended)
    }

    @Test
    fun `Open, finish with none seen is a truncated stream`() {
        feed(event(CONTENT))

        assertEquals(failed(ProviderFailure.UpstreamError), translator.finish())
        assertTrue(translator.ended)
    }

    @Test
    fun `Open, finish with no bytes at all is UpstreamError`() {
        assertEquals(failed(ProviderFailure.UpstreamError), translator.finish())
    }

    @Test
    fun `Open, fail gives the failure and ends`() {
        assertEquals(failed(ProviderFailure.UpstreamTimeout), translator.fail(ProviderFailure.UpstreamTimeout))
        assertTrue(translator.ended)
        assertEndsWithNothingMore()
    }

    @Test
    fun `Draining, a late fail is swallowed, so a timeout after a delivered DONE emits nothing`() {
        feed(event("[DONE]"))
        translator.delivered()

        assertTrue(translator.fail(ProviderFailure.UpstreamTimeout).isEmpty())
    }

    // Flux.timeout drops an item that loses the race with its timer, so the batch holding the terminal never arrives.
    @Test
    fun `a terminal the collector never got comes back from fail, with the rest of its feed`() {
        val events = feed(event(CONTENT) + event("[DONE]"))

        assertEquals(events, translator.fail(ProviderFailure.UpstreamTimeout))
        assertTrue(translator.fail(ProviderFailure.UpstreamTimeout).isEmpty())
    }

    @Test
    fun `a failure the collector never got comes back from fail, not the deadline's`() {
        feed(event("{not json"))

        assertEquals(failed(ProviderFailure.UpstreamError), translator.fail(ProviderFailure.UpstreamTimeout))
    }

    @Test
    fun `a terminal from finish the collector never got comes back from fail`() {
        feed(event(FINISH))
        val events = translator.finish()

        assertEquals(events, translator.fail(ProviderFailure.UpstreamTimeout))
    }

    // A deadline fires on the collector's side, so what makes the stream read as ended comes after the terminal.
    private fun assertDeadlineGetsTerminal(
        hasEnded: (OpenAiStreamTranslator) -> Boolean,
        end: (OpenAiStreamTranslator) -> Unit,
    ) {
        repeat(RACE_ROUNDS) {
            val racing = OpenAiStreamTranslator(OpenAiChunkReader(trees), OpenAiFailureClassifier(trees), LIMIT)
            val watching = AtomicBoolean()
            val deadline =
                CompletableFuture.supplyAsync {
                    watching.set(true)
                    while (!hasEnded(racing)) Thread.onSpinWait()
                    racing.fail(ProviderFailure.UpstreamTimeout)
                }
            while (!watching.get()) Thread.onSpinWait()

            end(racing)

            assertEquals(listOf(ProviderStreamEvent.Completed), deadline.get(RACE_WAIT_S, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `a deadline that sees a feed end the stream gets the terminal event`() {
        assertDeadlineGetsTerminal({ it.draining }, { it.feed(event("[DONE]").encodeToByteArray()) })
    }

    @Test
    fun `a deadline that sees finish end the stream gets the terminal event`() {
        assertDeadlineGetsTerminal({ it.ended }) {
            it.feed(event(FINISH).encodeToByteArray())
            it.finish()
        }
    }

    @Test
    fun `one chunk split over three feeds gives one Chunk`() {
        val text = event(CONTENT)
        val third = text.length / 3

        val events =
            feed(text.substring(0, third)) +
                feed(text.substring(third, 2 * third)) +
                feed(text.substring(2 * third))

        assertEquals(1, events.size)
    }

    @Test
    fun `nothing is read after a terminal in the same feed`() {
        val events = feed(event(CONTENT) + event("{not json") + event(CONTENT))

        assertEquals(2, events.size)
        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events[1])
    }

    @Test
    fun `nothing is read after DONE in the same feed`() {
        val events = feed(event("[DONE]") + event(CONTENT))

        assertEquals(listOf(ProviderStreamEvent.Completed), events)
    }

    private companion object {
        const val LIMIT = 1024
        const val RACE_ROUNDS = 20_000
        const val RACE_WAIT_S = 5L
        const val CONTENT = """{"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"Hi"}}]}"""
        const val FINISH =
            """{"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
    }
}
