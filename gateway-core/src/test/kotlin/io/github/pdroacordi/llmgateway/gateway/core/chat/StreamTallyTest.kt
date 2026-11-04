package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamTallyTest {
    private val estimator = UsageEstimator()

    private fun tally(request: ChatRequest = ASKING): StreamTally = StreamTally(request, DEPLOYMENT, estimator)

    private fun content(
        text: String?,
        index: Int = 0,
        usage: Usage? = null,
        id: String = "chatcmpl-1",
    ): ProviderStreamEvent.Chunk =
        ProviderStreamEvent.Chunk(
            ChatChunk(id, CREATED, MODEL, listOf(ChunkChoice(index, ChunkDelta(text))), usage),
        )

    private fun usageChunk(usage: Usage = PROVIDER_USAGE): ProviderStreamEvent.Chunk =
        ProviderStreamEvent.Chunk(ChatChunk("chatcmpl-1", CREATED, MODEL, emptyList(), usage))

    private fun StreamTally.feed(vararg events: ProviderStreamEvent): List<ChatStreamEvent> = events.flatMap { on(it) }

    @Test
    fun `Waiting, a forwarded chunk gives Started right before it`() {
        val first = content("Hi")

        assertEquals(
            listOf(ChatStreamEvent.Started(DEPLOYMENT), ChatStreamEvent.Chunk(first.chunk)),
            tally().on(first),
        )
    }

    @Test
    fun `a failure while an adapter holds the first chunk starts nothing, so it can still be a status`() {
        val tally = tally()
        val failure = ProviderFailure.ProviderUnavailable(null)

        assertEquals(listOf(ChatStreamEvent.Failed(failure)), tally.on(ProviderStreamEvent.Failed(failure)))
        assertFalse(tally.summary(cancelled = false).started)
    }

    @Test
    fun `an empty answer whose first chunk is the finish chunk starts and completes`() {
        val finish =
            ChatChunk(
                "chatcmpl-1",
                CREATED,
                MODEL,
                listOf(ChunkChoice(0, ChunkDelta(), FinishReason("stop"))),
            )
        val events = tally().feed(ProviderStreamEvent.Chunk(finish), usageChunk(), ProviderStreamEvent.Completed)

        assertEquals(ChatStreamEvent.Started(DEPLOYMENT), events.first())
        assertEquals(ChatStreamEvent.Chunk(finish), events[1])
        assertEquals(ChatStreamEvent.Completed(PROVIDER_USAGE), events.last())
    }

    @Test
    fun `Started comes once`() {
        val events = tally().feed(content("a"), content("b"), content("c"))

        assertEquals(1, events.count { it is ChatStreamEvent.Started })
        assertEquals(ChatStreamEvent.Started(DEPLOYMENT), events.first())
    }

    @Test
    fun `Waiting, Failed gives Failed and nothing started`() {
        val tally = tally()

        val events = tally.on(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout))

        assertEquals(listOf(ChatStreamEvent.Failed(ProviderFailure.UpstreamTimeout)), events)
        assertFalse(tally.summary(cancelled = false).started)
    }

    @Test
    fun `Waiting, Completed is a 200 with no chunk, so UpstreamError`() {
        val events = tally().on(ProviderStreamEvent.Completed)

        assertEquals(listOf(ChatStreamEvent.Failed(ProviderFailure.UpstreamError)), events)
    }

    @Test
    fun `DONE after only a dropped usage chunk is still UpstreamError`() {
        val events = tally(SILENT).feed(usageChunk(), ProviderStreamEvent.Completed)

        assertEquals(listOf(ChatStreamEvent.Failed(ProviderFailure.UpstreamError)), events)
    }

    @Test
    fun `Open, a chunk gives Chunk only`() {
        val tally = tally()
        tally.on(content("a"))
        val next = content("b")

        assertEquals(listOf(ChatStreamEvent.Chunk(next.chunk)), tally.on(next))
    }

    @Test
    fun `Open, Failed gives Failed`() {
        val tally = tally()
        tally.on(content("a"))

        assertEquals(
            listOf(ChatStreamEvent.Failed(ProviderFailure.UpstreamError)),
            tally.on(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)),
        )
    }

    @Test
    fun `Open, Completed with provider usage gives Completed with it, and nothing synthesised`() {
        val tally = tally()
        tally.feed(content("a"), usageChunk())

        assertEquals(listOf(ChatStreamEvent.Completed(PROVIDER_USAGE)), tally.on(ProviderStreamEvent.Completed))
    }

    @Test
    fun `Open, Completed with no usage synthesises a flagged chunk from the last chunk when the client asked`() {
        val tally = tally()
        tally.feed(content("first", id = "chatcmpl-first"), content("1234567", id = "chatcmpl-last"))

        val events = tally.on(ProviderStreamEvent.Completed)

        val expected = estimator.estimate(ASKING, listOf(12L))
        assertEquals(
            listOf(
                ChatStreamEvent.Chunk(ChatChunk("chatcmpl-last", CREATED, MODEL, emptyList(), expected)),
                ChatStreamEvent.Completed(expected),
            ),
            events,
        )
        assertTrue(expected.estimated)
    }

    @Test
    fun `Open, Completed with no usage and a client that did not ask gives only Completed`() {
        val tally = tally(SILENT)
        tally.on(content("1234"))

        val expected = estimator.estimate(SILENT, listOf(4L))
        assertEquals(listOf(ChatStreamEvent.Completed(expected)), tally.on(ProviderStreamEvent.Completed))
    }

    @Test
    fun `a request with no stream options counts as a client that did not ask`() {
        val tally = tally(REQUEST)
        tally.on(content("a"))

        assertEquals(1, tally.on(ProviderStreamEvent.Completed).size)
    }

    @Test
    fun `a usage chunk is dropped when the client did not ask, and counts`() {
        val tally = tally(SILENT)
        tally.on(content("a"))

        assertEquals(emptyList<ChatStreamEvent>(), tally.on(usageChunk()))
        assertEquals(listOf(ChatStreamEvent.Completed(PROVIDER_USAGE)), tally.on(ProviderStreamEvent.Completed))
    }

    @Test
    fun `a usage chunk is forwarded when the client asked`() {
        val tally = tally()
        tally.on(content("a"))
        val usage = usageChunk()

        assertEquals(listOf(ChatStreamEvent.Chunk(usage.chunk)), tally.on(usage))
    }

    @Test
    fun `a chunk with empty choices and no usage goes through, with its extras, whatever the client asked`() {
        val extras = JsonValue.Object(mapOf("prompt_filter_results" to JsonValue.Array(emptyList())))
        val filter = ProviderStreamEvent.Chunk(ChatChunk("chatcmpl-1", CREATED, MODEL, emptyList(), extras = extras))

        assertEquals(
            listOf(ChatStreamEvent.Started(DEPLOYMENT), ChatStreamEvent.Chunk(filter.chunk)),
            tally(SILENT).on(filter),
        )
    }

    @Test
    fun `Waiting, a dropped usage chunk starts nothing`() {
        val tally = tally(SILENT)

        assertEquals(emptyList<ChatStreamEvent>(), tally.on(usageChunk()))
        assertFalse(tally.summary(cancelled = true).started)
    }

    @Test
    fun `a provider's usage on a content chunk is kept, and the last one wins`() {
        val other = Usage(TokenCount(1), TokenCount(1))
        val tally = tally()
        tally.feed(content("a", usage = other), content("b", usage = PROVIDER_USAGE))

        assertEquals(listOf(ChatStreamEvent.Completed(PROVIDER_USAGE)), tally.on(ProviderStreamEvent.Completed))
    }

    @Test
    fun `two choices are counted apart`() {
        val tally = tally(SILENT)
        tally.feed(content("1234", index = 0), content("1", index = 1), content("5", index = 0))

        val summary = tally.summary(cancelled = true)

        // ceil(5 / 4) + ceil(1 / 4), where one count would give ceil(6 / 4).
        assertEquals(TokenCount(3), summary.usage.outputTokens)
    }

    @Test
    fun `a chunk with no content counts nothing and is forwarded`() {
        val tally = tally(SILENT)
        val empty = content(null)

        assertEquals(listOf(ChatStreamEvent.Started(DEPLOYMENT), ChatStreamEvent.Chunk(empty.chunk)), tally.on(empty))
        assertEquals(TokenCount(0), tally.summary(cancelled = true).usage.outputTokens)
    }

    @Test
    fun `nothing comes out after Ended`() {
        val tally = tally()
        tally.feed(content("a"), ProviderStreamEvent.Failed(ProviderFailure.UpstreamError))

        assertEquals(emptyList<ChatStreamEvent>(), tally.on(content("b")))
        assertEquals(emptyList<ChatStreamEvent>(), tally.on(ProviderStreamEvent.Completed))
        assertEquals(
            emptyList<ChatStreamEvent>(),
            tally.on(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)),
        )
        assertEquals(StreamOutcome.Failed(ProviderFailure.UpstreamError), tally.summary(cancelled = false).outcome)
    }

    @Test
    fun `the summary of a completed stream holds the provider's usage and its counts`() {
        val tally = tally()
        tally.feed(content("a"), content("b"), usageChunk(), ProviderStreamEvent.Completed)

        assertEquals(
            StreamSummary(DEPLOYMENT, StreamOutcome.Completed, PROVIDER_USAGE, started = true, chunks = 3),
            tally.summary(cancelled = false),
        )
    }

    @Test
    fun `the synthesised chunk counts in the summary`() {
        val tally = tally()
        tally.feed(content("a"), ProviderStreamEvent.Completed)

        assertEquals(2, tally.summary(cancelled = false).chunks)
    }

    @Test
    fun `no terminal and cancelled gives Cancelled with an estimate`() {
        val tally = tally()
        tally.on(content("12345678"))

        val summary = tally.summary(cancelled = true)

        assertEquals(StreamOutcome.Cancelled, summary.outcome)
        assertEquals(estimator.estimate(ASKING, listOf(8L)), summary.usage)
        assertTrue(summary.started)
    }

    @Test
    fun `cancelled before any chunk gives a zero output and started false`() {
        val summary = tally().summary(cancelled = true)

        assertEquals(StreamOutcome.Cancelled, summary.outcome)
        assertEquals(TokenCount(0), summary.usage.outputTokens)
        assertFalse(summary.started)
        assertEquals(0, summary.chunks)
    }

    @Test
    fun `no terminal and not cancelled gives Crashed`() {
        val tally = tally()
        tally.on(content("a"))

        assertEquals(StreamOutcome.Crashed, tally.summary(cancelled = false).outcome)
    }

    @Test
    fun `a terminal beats a late cancellation`() {
        val completed = tally()
        completed.feed(content("a"), usageChunk(), ProviderStreamEvent.Completed)
        val failed = tally()
        failed.feed(content("a"), ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout))

        assertEquals(StreamOutcome.Completed, completed.summary(cancelled = true).outcome)
        assertEquals(PROVIDER_USAGE, completed.summary(cancelled = true).usage)
        assertEquals(StreamOutcome.Failed(ProviderFailure.UpstreamTimeout), failed.summary(cancelled = true).outcome)
    }

    @Test
    fun `a Waiting Failed reports the mapped failure and a Waiting Completed reports UpstreamError`() {
        val failed = tally()
        failed.on(ProviderStreamEvent.Failed(ProviderFailure.ModelNotFound))
        val empty = tally()
        empty.on(ProviderStreamEvent.Completed)

        assertEquals(StreamOutcome.Failed(ProviderFailure.ModelNotFound), failed.summary(cancelled = false).outcome)
        assertEquals(StreamOutcome.Failed(ProviderFailure.UpstreamError), empty.summary(cancelled = false).outcome)
    }

    @Test
    fun `a canary in a delta is absent from every toString`() {
        val tally = tally()
        val events = tally.feed(content(CANARY), ProviderStreamEvent.Completed)

        (events + tally.summary(cancelled = false)).forEach { assertFalse(CANARY in it.toString()) { it.toString() } }
    }

    private companion object {
        const val CREATED = 1793750400L
        const val CANARY = "canary-completion"
        val MODEL = ModelName("mock-chat")
        val DEPLOYMENT = Deployment(DeploymentId("openai"), ProviderId("openai"), MODEL)
        val MESSAGES = listOf(ChatMessage(Role("user"), MessageContent.Text("Hi")))
        val REQUEST = ChatRequest(MODEL, MESSAGES)
        val ASKING = REQUEST.copy(streamOptions = StreamOptions(includeUsage = true))
        val SILENT = REQUEST.copy(streamOptions = StreamOptions(includeUsage = false))
        val PROVIDER_USAGE = Usage(TokenCount(14), TokenCount(2))
    }
}
