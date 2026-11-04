package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.testsupport.SharedMockProvider
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.net.ServerSocket
import java.time.Duration

// No retries in the adapter, so one stream is one journal entry; every test can count on that.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnthropicChatStreamIntegrationTest {
    private val fixture = AnthropicMockFixture()

    @BeforeEach
    fun resetMock() {
        SharedMockProvider.reset()
    }

    @AfterEach
    fun closeAdapters() {
        fixture.close()
    }

    private fun stream(adapter: AnthropicChatAdapter = fixture.adapter()): List<ProviderStreamEvent> =
        runBlocking { adapter.stream(AnthropicMockFixture.call()).toList() }

    private fun List<ProviderStreamEvent>.chunks(): List<ChatChunk> =
        filterIsInstance<ProviderStreamEvent.Chunk>().map { it.chunk }

    private fun List<ProviderStreamEvent>.text(): String =
        chunks().flatMap { it.choices }.joinToString("") { it.delta.content.orEmpty() }

    // First in the class, so the first stream in this JVM is the one BlockHound sees.
    @Test
    @Order(1)
    fun `200 streams pass BlockHound on a non-blocking thread, the first one included`() {
        fixture.scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")
        val adapter = fixture.adapter()
        adapter.warmUp()

        val streams =
            Flux
                .range(0, STREAMS)
                .flatMap({ onNonBlockingThread(adapter) }, CONCURRENCY)
                .collectList()
                .block(BLOCK_LIMIT)
                .orEmpty()

        assertEquals(STREAMS, streams.size)
        assertTrue(streams.all { it.last() == ProviderStreamEvent.Completed }) { streams.toString() }
    }

    @Suppress("InjectDispatcher")
    private fun onNonBlockingThread(adapter: AnthropicChatAdapter) =
        mono(Dispatchers.Unconfined) { adapter.stream(AnthropicMockFixture.call()).toList() }
            .subscribeOn(Schedulers.parallel())

    @Test
    fun `a success gives its chunks, the finish, the usage chunk and Completed`() {
        fixture.scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val events = stream()
        val chunks = events.chunks()

        assertEquals("Hi there", events.text())
        assertEquals(
            "stop",
            chunks[chunks.size - 2]
                .choices
                .single()
                .finishReason
                ?.value,
        )
        assertTrue(chunks.last().choices.isEmpty())
        assertTrue(checkNotNull(chunks.last().usage).inputTokens.value > 0)
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `the provider sees stream true, an event-stream Accept and the request ID`() {
        stream()

        val entry = fixture.journal().single()
        assertTrue(""""stream":true""" in entry.at("/body").stringValue()) { entry.toString() }
        assertEquals("text/event-stream", entry.at("/headers/accept/0").stringValue())
        assertEquals(
            AnthropicMockFixture.REQUEST_ID.toString(),
            entry.at("/headers/x-client-request-id/0").stringValue(),
        )
    }

    @Test
    fun `a refused stream is one Failed event and no upstream call`() {
        val before = fixture.lastSeq()
        val extras = JsonValue.Object(mapOf("n" to JsonValue.Number("2")))
        val events = runBlocking { fixture.adapter().stream(AnthropicMockFixture.call(extras)).toList() }

        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UnsupportedByProvider("unsupported_value", "n"))),
            events,
        )
        assertEquals(before, fixture.lastSeq())
    }

    @Test
    fun `usage omit gives no usage chunk, and Completed`() {
        fixture.scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val events = stream()

        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `an upstream status before the body is a Failed first, with one journal entry`() {
        fixture.scenario("""{"otherwise":{"error":{"status":429,"retryAfterSeconds":1}}}""")

        val events = stream()

        assertTrue(events.single() is ProviderStreamEvent.Failed) { events.toString() }
        assertEquals(1, fixture.journal().size)
    }

    @Test
    fun `an in-band overload right after message_start commits nothing`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"stream_error","afterPieces":0}}}""")

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.ProviderUnavailable(null))), stream())
    }

    @Test
    fun `a closed port is ProviderUnavailable`() {
        val port = ServerSocket(0).use { it.localPort }

        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.ProviderUnavailable(null))),
            stream(fixture.adapter(baseUrl = "http://127.0.0.1:$port/v1")),
        )
    }

    @Test
    fun `hang at the first-byte deadline is UpstreamTimeout, and the provider sees the call cancelled`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val config = AnthropicMockFixture.STREAM.copy(firstByteTimeout = Duration.ofMillis(500))

        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)),
            stream(fixture.adapter(stream = config)),
        )
        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    @Test
    fun `stall at the idle deadline gives the pieces so far, then UpstreamTimeout`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"stall","afterPieces":2}}}""")
        val config = AnthropicMockFixture.STREAM.copy(idleTimeout = Duration.ofMillis(500))

        val events = stream(fixture.adapter(stream = config))

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout), events.last())
        assertEquals(2, events.count { it is ProviderStreamEvent.Chunk })
        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    @Test
    fun `the total deadline cuts a stream that never goes idle`() {
        fixture.scenario("""{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"$LONG"}}}""")
        val config = AnthropicMockFixture.STREAM.copy(totalTimeout = Duration.ofSeconds(1))

        val events = stream(fixture.adapter(stream = config))

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout), events.last())
        assertTrue(events.size > 2) { events.size.toString() }
        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    @Test
    fun `disconnect mid-body is UpstreamError after the pieces so far`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"disconnect","afterPieces":2}}}""")

        val events = stream()

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
        assertEquals(2, events.count { it is ProviderStreamEvent.Chunk })
    }

    @Test
    fun `a malformed event ends the stream with UpstreamError, and the rest is not read`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"malformed","afterPieces":1}}}""")

        val events = stream()

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
        assertEquals(1, events.count { it is ProviderStreamEvent.Chunk })
    }

    @Test
    fun `an event over the limit is UpstreamError`() {
        val text = "a".repeat(2048)
        fixture.scenario("""{"otherwise":{"content":{"mode":"fixed","text":"$text"}}}""")
        val config = AnthropicMockFixture.STREAM.copy(maxEventBytes = 1024)

        assertEquals(
            ProviderStreamEvent.Failed(ProviderFailure.UpstreamError),
            stream(fixture.adapter(stream = config)).last(),
        )
    }

    @Test
    fun `a cancelled collector leaves client_cancelled, with fewer pieces sent than the total`() {
        fixture.scenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$LONG"}}}""")

        runBlocking { fixture.adapter().stream(AnthropicMockFixture.call()).first() }

        fixture.eventually { fixture.outcome() == "client_cancelled" }
        assertTrue(
            fixture
                .journal()
                .single()
                .at("/outcome/piecesSent")
                .intValue() < WORDS,
        )
    }

    @Test
    fun `cancelling a collector that waits for the first byte cancels the upstream call`() {
        fixture.scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val adapter = fixture.adapter()
        val before = fixture.lastSeq()

        runBlocking {
            val collector =
                async(start = CoroutineStart.UNDISPATCHED) { adapter.stream(AnthropicMockFixture.call()).toList() }
            fixture.eventually { fixture.lastSeq() > before }
            collector.cancel()
        }

        fixture.eventually { fixture.outcome() == "client_cancelled" }
    }

    @Test
    fun `the pool recovers after 20 cancelled streams, with 2 connections`() {
        fixture.scenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$LONG"}}}""")
        val adapter = fixture.adapter(pool = AnthropicMockFixture.POOL.copy(maxConnections = 2))

        repeat(CANCELLED_IN_A_ROW) { runBlocking { adapter.stream(AnthropicMockFixture.call()).first() } }
        fixture.scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi"}}}""")

        assertEquals(ProviderStreamEvent.Completed, stream(adapter).last())
    }

    private companion object {
        const val STREAMS = 200
        const val CONCURRENCY = 16
        const val CANCELLED_IN_A_ROW = 20
        const val WORDS = 200
        val BLOCK_LIMIT: Duration = Duration.ofMinutes(2)
        val LONG = "word ".repeat(WORDS)
    }
}
