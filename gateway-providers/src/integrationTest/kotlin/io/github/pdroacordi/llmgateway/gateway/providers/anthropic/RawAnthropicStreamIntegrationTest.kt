package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What the mock cannot do: bad framing, a reset, and a provider that never sends `message_stop`. */
class RawAnthropicStreamIntegrationTest {
    private class RawProvider(
        answer: (Socket) -> Boolean,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val connections = AtomicInteger()
        private val acceptor =
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    thread(isDaemon = true) { serve(socket, answer) }
                }
            }

        private fun serve(
            socket: Socket,
            answer: (Socket) -> Boolean,
        ) {
            try {
                while (socket.getInputStream().readRequest() && answer(socket)) {
                    continue
                }
            } catch (_: SocketException) {
                socket.close()
            }
        }

        private fun InputStream.readRequest(): Boolean {
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val byte = read()
                if (byte < 0) return false
                head.append(byte.toChar())
            }
            val length =
                Regex("(?i)content-length: *(\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            readNBytes(length)
            return true
        }

        override fun close() {
            server.close()
            acceptor.join(Duration.ofSeconds(5))
        }
    }

    private val closeables = mutableListOf<AutoCloseable>()
    private val fixture = AnthropicMockFixture().also { closeables += it }

    @AfterEach
    fun close() {
        closeables.forEach { it.close() }
    }

    private fun provider(answer: (Socket) -> Boolean): RawProvider = RawProvider(answer).also { closeables += it }

    private fun adapter(provider: RawProvider): AnthropicChatAdapter =
        fixture.adapter(baseUrl = "http://127.0.0.1:${provider.port}/v1")

    private fun stream(adapter: AnthropicChatAdapter): List<ProviderStreamEvent> =
        runBlocking { adapter.stream(AnthropicMockFixture.call()).toList() }

    private fun stream(provider: RawProvider): List<ProviderStreamEvent> = stream(adapter(provider))

    private fun text(events: List<ProviderStreamEvent>): String =
        events
            .filterIsInstance<ProviderStreamEvent.Chunk>()
            .flatMap { it.chunk.choices }
            .joinToString("") { it.delta.content.orEmpty() }

    private fun Socket.head() {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
        getOutputStream().write(head.encodeToByteArray())
    }

    private fun Socket.chunk(text: String) {
        val bytes = text.encodeToByteArray()
        getOutputStream().apply {
            write("${bytes.size.toString(radix = 16)}\r\n".encodeToByteArray())
            write(bytes)
            write("\r\n".encodeToByteArray())
            flush()
        }
    }

    private fun Socket.end() {
        getOutputStream().apply {
            write("0\r\n\r\n".encodeToByteArray())
            flush()
        }
    }

    // One request per connection, closed after the answer.
    private fun Socket.closing(block: Socket.() -> Unit): Boolean {
        use { block() }
        return false
    }

    @Test
    fun `a close after message_delta with no message_stop completes, usage first`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(START + text("Hi") + DELTA)
                    end()
                }
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Completed, events.last())
        assertTrue(events.filterIsInstance<ProviderStreamEvent.Chunk>().any { it.chunk.usage != null })
    }

    @Test
    fun `a close before message_delta is a truncated stream`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(START + text("Hi"))
                    end()
                }
            }

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), stream(provider).last())
    }

    @Test
    fun `a 200 with an empty body is UpstreamError`() {
        val provider =
            provider {
                it.closing {
                    head()
                    end()
                }
            }

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), stream(provider))
    }

    @Test
    fun `a reset mid-body is UpstreamError after what was read`() {
        val provider =
            provider {
                it.head()
                it.chunk(START + text("Hi"))
                Thread.sleep(SPLIT_PAUSE_MS)
                it.setSoLinger(true, 0)
                it.close()
                false
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["ZZZ\r\n\r\n"])
    fun `broken chunked framing first is UpstreamError, not a bug`(framing: String) {
        val provider =
            provider {
                it.closing {
                    head()
                    getOutputStream().write(framing.encodeToByteArray())
                }
            }

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), stream(provider))
    }

    @Test
    fun `broken chunked framing during the drain stays silent, after Completed`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(START + text("Hi") + DELTA + STOP)
                    Thread.sleep(SPLIT_PAUSE_MS)
                    getOutputStream().write("ZZZ\r\n\r\n".encodeToByteArray())
                }
            }

        assertEquals(ProviderStreamEvent.Completed, stream(provider).last())
    }

    @Test
    fun `a provider that keeps the body open after message_stop costs at most the drain, and a new connection`() {
        val provider =
            provider {
                it.head()
                it.chunk(START + text("Hi") + DELTA + STOP)
                true
            }
        val adapter = adapter(provider)
        val started = System.nanoTime()

        val first = stream(adapter)
        val second = stream(adapter)

        assertEquals(ProviderStreamEvent.Completed, first.last())
        assertEquals(ProviderStreamEvent.Completed, second.last())
        assertEquals(2, provider.connections.get())
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < TWO_DRAINS) { "drain took too long" }
    }

    @Test
    fun `a connection is reused after a completed stream`() {
        val provider =
            provider {
                it.head()
                it.chunk(START + text("Hi") + DELTA + STOP)
                it.end()
                true
            }
        val adapter = adapter(provider)

        stream(adapter)
        stream(adapter)

        assertEquals(1, provider.connections.get())
    }

    private fun text(value: String): String =
        event("content_block_delta", """{"index":0,"delta":{"type":"text_delta","text":"$value"}}""")

    private companion object {
        const val SPLIT_PAUSE_MS = 50L
        val TWO_DRAINS: Duration = Duration.ofMillis(3500)

        fun event(
            name: String,
            json: String,
        ): String = "event: $name\ndata: $json\n\n"

        val START =
            event(
                "message_start",
                """{"message":{"id":"m1","model":"claude-x","usage":{"input_tokens":3,"output_tokens":1}}}""",
            )
        val DELTA = event("message_delta", """{"delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}""")
        val STOP = event("message_stop", """{}""")
    }
}
