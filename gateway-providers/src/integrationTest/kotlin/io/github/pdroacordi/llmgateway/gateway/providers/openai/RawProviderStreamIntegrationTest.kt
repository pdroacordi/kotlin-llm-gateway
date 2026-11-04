package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/**
 * What the mock cannot send, over a raw socket. [RawProvider] answers each request of a kept-alive connection with
 * [answer], which returns false to stop serving that connection.
 */
class RawProviderStreamIntegrationTest {
    private class RawProvider(
        answer: (Socket) -> Boolean,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        val port: Int = server.localPort

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

    @AfterEach
    fun close() {
        closeables.forEach { it.close() }
    }

    private fun provider(answer: (Socket) -> Boolean): RawProvider = RawProvider(answer).also { closeables += it }

    private fun adapter(
        provider: RawProvider,
        firstByte: Duration = Duration.ofSeconds(10),
        idle: Duration = Duration.ofSeconds(10),
    ): OpenAiChatAdapter =
        OpenAiChatAdapter(
            OpenAiAdapterConfig(
                baseUrl = URI.create("http://127.0.0.1:${provider.port}/v1"),
                apiKey = ApiKey("provider-key-not-real"),
                connectTimeout = Duration.ofSeconds(5),
                requestTimeout = Duration.ofSeconds(10),
                maxResponseBytes = 1_048_576,
                pool =
                    PoolConfig(
                        maxConnections = 4,
                        pendingAcquireTimeout = Duration.ofSeconds(5),
                        maxIdleTime = Duration.ofSeconds(30),
                        maxLifeTime = Duration.ofMinutes(5),
                    ),
                stream = StreamConfig(firstByte, idle, Duration.ofSeconds(120), 1_048_576),
            ),
            JsonMapper.builder().build(),
            Clock.systemUTC(),
        ).also { closeables += it }

    private fun stream(provider: RawProvider): List<ProviderStreamEvent> = stream(adapter(provider))

    private fun stream(adapter: OpenAiChatAdapter): List<ProviderStreamEvent> =
        runBlocking { adapter.stream(CALL).toList() }

    private fun text(events: List<ProviderStreamEvent>): String =
        events
            .filterIsInstance<ProviderStreamEvent.Chunk>()
            .flatMap { it.chunk.choices }
            .joinToString("") { it.delta.content.orEmpty() }

    private fun Socket.head(
        status: String = "200 OK",
        contentType: String = "text/event-stream",
    ) {
        val head =
            "HTTP/1.1 $status\r\nContent-Type: $contentType\r\nTransfer-Encoding: chunked\r\n\r\n"
        getOutputStream().write(head.encodeToByteArray())
    }

    private fun Socket.chunk(bytes: ByteArray) {
        getOutputStream().apply {
            write("${bytes.size.toString(radix = 16)}\r\n".encodeToByteArray())
            write(bytes)
            write("\r\n".encodeToByteArray())
            flush()
        }
    }

    private fun Socket.chunk(text: String) = chunk(text.encodeToByteArray())

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
    fun `CRLF framing, comments, event lines and unknown fields read as plain events`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(": keep-alive\r\n\r\n")
                    chunk("event: delta\r\nid: 1\r\nretry: 10\r\ndata: ${chunkJson("Hi")}\r\n\r\n")
                    chunk(FINISH.crlf() + "data: [DONE]\r\n\r\n")
                    end()
                }
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `a multi-byte character split across reads stays whole`() {
        val bytes = "data: ${chunkJson("é😀")}\n\n".encodeToByteArray()
        val split = bytes.indexOf(0xF0.toByte()) + 2
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(bytes.copyOfRange(0, split))
                    Thread.sleep(SPLIT_PAUSE_MS)
                    chunk(bytes.copyOfRange(split, bytes.size))
                    chunk(FINISH + DONE)
                    end()
                }
            }

        assertEquals("é😀", text(stream(provider)))
    }

    @Test
    fun `an in-band error first is the mapped failure, with no chunk`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk(
                        """data: {"error":{"message":"m","type":"x","param":null,"code":"insufficient_quota"}}""" +
                            "\n\n",
                    )
                    end()
                }
            }

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamQuotaExhausted)), stream(provider))
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
    fun `a 200 with an HTML body is UpstreamError`() {
        val provider =
            provider {
                it.closing {
                    head(contentType = "text/html")
                    chunk("<html>\n\n<body>canary: proxy page</body>\n\n</html>\n")
                    end()
                }
            }

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), stream(provider))
    }

    @ParameterizedTest
    @ValueSource(strings = ["garbage", "text/html; charset=", "text/html; charset=no-such-charset"])
    fun `a 200 with a Content-Type Spring cannot parse is still read`(contentType: String) {
        val provider =
            provider {
                it.closing {
                    head(contentType = contentType)
                    chunk("data: ${chunkJson("Hi")}\n\n" + FINISH + DONE)
                    end()
                }
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `a 500 with a Content-Type Spring cannot parse maps by status`() {
        val provider =
            provider {
                it.closing {
                    val body = "<html>x</html>"
                    val head =
                        "HTTP/1.1 500 Internal Server Error\r\nContent-Type: garbage\r\n" +
                            "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n"
                    getOutputStream().write((head + body).encodeToByteArray())
                }
            }

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), stream(provider))
    }

    @Test
    fun `EOF after a finish_reason with no DONE is Completed`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk("data: ${chunkJson("Hi")}\n\n" + FINISH)
                    end()
                }
            }

        assertEquals(ProviderStreamEvent.Completed, stream(provider).last())
    }

    @Test
    fun `EOF with no finish_reason and no DONE is a truncated stream`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk("data: ${chunkJson("Hi")}\n\n")
                    end()
                }
            }

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), stream(provider).last())
    }

    @Test
    fun `a reset mid-body is UpstreamError after what was read`() {
        val provider =
            provider {
                head(it)
                it.chunk("data: ${chunkJson("Hi")}\n\n")
                Thread.sleep(SPLIT_PAUSE_MS)
                it.setSoLinger(true, 0)
                it.close()
                false
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
    }

    private fun head(socket: Socket) = socket.head()

    @Test
    fun `a closed connection mid-body is UpstreamError`() {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk("data: ${chunkJson("Hi")}\n\n")
                }
            }

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), stream(provider).last())
    }

    @ParameterizedTest
    @MethodSource("badFraming")
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

    @ParameterizedTest
    @MethodSource("badFraming")
    fun `broken chunked framing mid-stream is UpstreamError after what was read`(framing: String) {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk("data: ${chunkJson("Hi")}\n\n")
                    Thread.sleep(SPLIT_PAUSE_MS)
                    getOutputStream().write(framing.encodeToByteArray())
                }
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError), events.last())
    }

    @ParameterizedTest
    @MethodSource("badFraming")
    fun `broken chunked framing during the drain stays silent, after Completed`(framing: String) {
        val provider =
            provider {
                it.closing {
                    head()
                    chunk("data: ${chunkJson("Hi")}\n\n" + FINISH + DONE)
                    Thread.sleep(SPLIT_PAUSE_MS)
                    getOutputStream().write(framing.encodeToByteArray())
                }
            }

        val events = stream(provider)

        assertEquals("Hi", text(events))
        assertEquals(ProviderStreamEvent.Completed, events.last())
        assertEquals(1, events.count { it !is ProviderStreamEvent.Chunk })
    }

    @Test
    fun `a connection is reused after a completed stream`() {
        val provider = provider(::wholeStream)
        val adapter = adapter(provider)

        val first = stream(adapter)
        val second = stream(adapter)

        assertEquals(ProviderStreamEvent.Completed, first.last())
        assertEquals(ProviderStreamEvent.Completed, second.last())
        assertEquals(1, provider.connections.get())
    }

    @Test
    fun `a provider that keeps the body open after DONE costs at most the drain, and a new connection`() {
        val provider =
            provider {
                it.head()
                it.chunk("data: ${chunkJson("Hi")}\n\n" + FINISH + DONE)
                true
            }
        val adapter = adapter(provider)

        val first = stream(adapter)
        val second = stream(adapter)

        assertEquals(ProviderStreamEvent.Completed, first.last())
        assertEquals(ProviderStreamEvent.Completed, second.last())
        assertEquals(2, provider.connections.get())
    }

    // Demand reaches the provider's socket: with no collector demand FluxReceive stops reading, TCP fills, and the
    // provider's write blocks. Idle is raised, as Flux.timeout does not see demand.
    @Test
    fun `a collector that stops consuming stops the provider's writes, and loses nothing when it resumes`() {
        val written = AtomicLong()
        val provider =
            provider {
                it.closing {
                    sendBufferSize = SEND_BUFFER
                    head()
                    repeat(BACK_PRESSURE_EVENTS) { sequence ->
                        chunk("data: ${chunkJson("$sequence $PADDING")}\n\n")
                        written.incrementAndGet()
                    }
                    chunk(FINISH + DONE)
                    end()
                }
            }
        val adapter = adapter(provider, idle = Duration.ofSeconds(60))
        val resume = CompletableDeferred<Unit>()
        val texts = mutableListOf<String>()

        val plateau =
            runBlocking {
                val collecting =
                    launch {
                        adapter.stream(CALL).collect { event ->
                            resume.await()
                            if (event is ProviderStreamEvent.Chunk) {
                                texts += event.chunk.choices.joinToString("") { it.delta.content.orEmpty() }
                            }
                        }
                    }
                val stable = awaitPlateau(written)
                resume.complete(Unit)
                collecting.join()
                stable
            }

        assertTrue(plateau < BACK_PRESSURE_EVENTS) { "$plateau of $BACK_PRESSURE_EVENTS written with no demand" }
        val sequences = texts.filter { it.isNotEmpty() }.map { it.substringBefore(' ').toInt() }
        assertEquals((0 until BACK_PRESSURE_EVENTS).toList(), sequences)
    }

    private suspend fun awaitPlateau(written: AtomicLong): Long {
        var last = -1L
        while (true) {
            val now = written.get()
            if (now == last && now > 0) return now
            last = now
            Mono.delay(PLATEAU_WINDOW).awaitSingle()
        }
    }

    @Test
    fun `no first byte by the deadline is UpstreamTimeout`() {
        val provider =
            provider {
                it.head()
                it.getOutputStream().flush()
                Thread.sleep(HOLD_MS)
                false
            }

        val events = stream(adapter(provider, firstByte = Duration.ofMillis(300)))

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamTimeout)), events)
    }

    private fun wholeStream(socket: Socket): Boolean {
        socket.head()
        socket.chunk("data: ${chunkJson("Hi")}\n\n")
        socket.chunk(FINISH + DONE)
        socket.end()
        return true
    }

    private fun String.crlf(): String = replace("\n", "\r\n")

    companion object {
        private const val LONG_LINE = 20_000
        private const val SEND_BUFFER = 16 * 1024
        private const val BACK_PRESSURE_EVENTS = 20_000
        private val PADDING = "x".repeat(1024)
        private val PLATEAU_WINDOW: Duration = Duration.ofSeconds(1)
        private const val SPLIT_PAUSE_MS = 50L
        private const val HOLD_MS = 1500L
        private const val DONE = "data: [DONE]\n\n"
        private const val FINISH =
            """data: {"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""" +
                "\n\n"
        private val CALL =
            ChatCall(
                RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("m")),
                ChatRequest(ModelName("m"), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi")))),
            )

        @JvmStatic
        fun badFraming(): List<String> = listOf("ZZZ\r\n\r\n", "a".repeat(LONG_LINE))

        private fun chunkJson(text: String): String =
            """{"id":"c","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"$text"}}]}"""
    }
}
