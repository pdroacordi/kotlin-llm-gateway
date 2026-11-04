package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.netty.channel.ChannelOption
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.reactor.awaitSingle
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import reactor.core.publisher.Mono
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Its own context: the timeout of [SlowClientStreamTest] must outlast its plateau, this one must not. The small send
 * buffer is the test's: a socket reports write progress only as its buffer drains, in steps that scale with its size.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["llmgw.server.client-write-timeout=1s"],
)
@Import(FakeChatModelPort.Wiring::class, StalledClientStreamTest.SmallSendBuffer::class)
@ExtendWith(OutputCaptureExtension::class)
class StalledClientStreamTest(
    @Autowired private val provider: FakeChatModelPort,
    @LocalServerPort private val port: Int,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class SmallSendBuffer {
        @Bean
        fun boundedSendBuffer(): NettyServerCustomizer =
            NettyServerCustomizer { http -> http.childOption(ChannelOption.SO_SNDBUF, SEND_BUFFER) }
    }

    private val cancelled = AtomicBoolean()
    private var mark = 0

    @BeforeEach
    fun reset() {
        provider.reset()
    }

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    private fun ownLog(output: CapturedOutput): String = output.all.substring(mark)

    private fun endless(): Flow<ProviderStreamEvent> =
        flow<ProviderStreamEvent> {
            while (true) emit(FakeChatModelPort.chunk(PADDING))
        }.onCompletion { cancelled.set(true) }

    private fun finite(frames: Int): Flow<ProviderStreamEvent> =
        flow {
            repeat(frames) { emit(FakeChatModelPort.chunk(PADDING)) }
            emit(ProviderStreamEvent.Completed)
        }

    private fun trickling(): Flow<ProviderStreamEvent> =
        flow<ProviderStreamEvent> {
            while (true) {
                emit(FakeChatModelPort.chunk(PADDING))
                Mono.delay(TRICKLE).awaitSingle()
            }
        }.onCompletion { cancelled.set(true) }

    private fun assertCancelled(output: CapturedOutput) {
        await().atMost(WAIT).until { cancelled.get() && "Stream cancelled" in ownLog(output) }
        val lines = ownLog(output).lines().filter { "Stream cancelled" in it }
        assertEquals(1, lines.size) { output.all }
        assertTrue("outcome=cancelled" in lines.single()) { lines.single() }
        assertFalse(" ERROR " in ownLog(output)) { output.all }
    }

    // Reads what the kernel held, then EOF or a reset. A socket that stays open ends in SocketTimeoutException.
    private fun assertClosed(input: InputStream) {
        val buffer = ByteArray(READ_SIZE)
        try {
            while (input.read(buffer) >= 0) continue
        } catch (_: SocketException) {
            return
        }
    }

    @Test
    fun `a client that stops reading is cut, not before the timeout, and the socket closes`(output: CapturedOutput) {
        provider.streamAnswer = { endless() }
        val started = System.nanoTime()

        stalledSocket().use { socket ->
            socket.getOutputStream().write(REQUEST.encodeToByteArray())

            assertCancelled(output)
            assertTrue(Duration.ofNanos(System.nanoTime() - started) >= TIMEOUT) { "cut before the timeout" }
            assertClosed(socket.getInputStream())
        }
    }

    // Each frame joins the queue after the kernel stopped taking bytes: that is not progress.
    @Test
    fun `a stopped client on a slow stream is cut while frames keep arriving`(output: CapturedOutput) {
        provider.streamAnswer = { trickling() }

        stalledSocket().use { socket ->
            socket.getOutputStream().write(REQUEST.encodeToByteArray())

            assertCancelled(output)
            assertClosed(socket.getInputStream())
        }
    }

    @Test
    fun `a stalled client is still cut on a second request of a kept-alive connection`(output: CapturedOutput) {
        stalledSocket().use { socket ->
            socket.getOutputStream().write(REQUEST.encodeToByteArray())
            readBody(socket.getInputStream())
            provider.streamAnswer = { endless() }

            socket.getOutputStream().write(REQUEST.encodeToByteArray())

            assertCancelled(output)
            assertClosed(socket.getInputStream())
        }
    }

    // 640 and 160 KB/s, below what drains the queued frames inside the timeout, so a timer on queue time cuts both.
    @ParameterizedTest
    @CsvSource("32768, 50, 6000", "8192, 50, 6000")
    fun `a client that reads slowly but steadily is never cut, and gets every frame`(
        bytes: Int,
        pauseMs: Long,
        paceForMs: Long,
        output: CapturedOutput,
    ) {
        provider.streamAnswer = { finite(FRAMES) }

        val body =
            stalledSocket().use { socket ->
                socket.getOutputStream().write(REQUEST.encodeToByteArray())
                readPaced(socket.getInputStream(), bytes, pauseMs, paceForMs)
            }

        assertTrue(body.endsWith("0\r\n\r\n")) { body.takeLast(FRAME_TAIL) }
        assertEquals(FRAMES, Regex("\"id\":").findAll(body).count())
        assertFalse("Stream cancelled" in ownLog(output)) { output.all }
    }

    // 32 KB/s. Only where SO_SNDBUF bounds what a write hands the kernel: Windows takes hundreds of KB at once, so
    // the next progress is further away than the timeout at this rate.
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a much slower reader is not cut either`(output: CapturedOutput) {
        `a client that reads slowly but steadily is never cut, and gets every frame`(4096, 125, 6000, output)
    }

    // No write completes for seconds, so only the bytes of the one frame show progress. Linux only, as above.
    @Test
    @EnabledOnOs(OS.LINUX)
    fun `a reader of one large frame in bursts is not cut, though no write completes`(output: CapturedOutput) {
        provider.streamAnswer =
            {
                flow {
                    emit(FakeChatModelPort.chunk("x".repeat(LARGE_FRAME)))
                    emit(ProviderStreamEvent.Completed)
                }
            }

        val body =
            stalledSocket().use { socket ->
                socket.getOutputStream().write(REQUEST.encodeToByteArray())
                readPaced(socket.getInputStream(), BURST, BURST_PAUSE_MS, BURST_PACE_MS)
            }

        assertTrue(body.endsWith(LAST_CHUNK)) { body.takeLast(FRAME_TAIL) }
        assertFalse("Stream cancelled" in ownLog(output)) { output.all }
    }

    @Test
    fun `a slow provider with nothing pending for the client is not a stall`() {
        provider.streamAnswer =
            {
                flow {
                    emit(FakeChatModelPort.chunk("Hi"))
                    Mono.delay(TIMEOUT.multipliedBy(SLOW_PROVIDER_TIMEOUTS)).awaitSingle()
                    emit(FakeChatModelPort.chunk(" there"))
                    emit(ProviderStreamEvent.Completed)
                }
            }

        stalledSocket().use { socket ->
            socket.getOutputStream().write(REQUEST.encodeToByteArray())

            readBody(socket.getInputStream())
        }
    }

    private fun stalledSocket(): Socket =
        Socket().apply {
            receiveBufferSize = RECEIVE_BUFFER
            soTimeout = READ_TIMEOUT_MS
            connect(InetSocketAddress("127.0.0.1", this@StalledClientStreamTest.port))
        }

    // Paced for paceForMs, then at full speed, so a slow rate need not last the whole body.
    private fun readPaced(
        input: InputStream,
        bytes: Int,
        pauseMs: Long,
        paceForMs: Long,
    ): String {
        val body = ByteArrayOutputStream()
        val paceEnd = System.nanoTime() + Duration.ofMillis(paceForMs).toNanos()
        val buffer = ByteArray(READ_SIZE)
        var sinceLastPause = 0
        var tail = ""
        while (!tail.endsWith(LAST_CHUNK)) {
            val read = input.read(buffer)
            if (read < 0) break
            body.write(buffer, 0, read)
            tail = (tail + String(buffer, 0, read, Charsets.ISO_8859_1)).takeLast(LAST_CHUNK.length)
            sinceLastPause += read
            if (sinceLastPause >= bytes && System.nanoTime() < paceEnd) {
                sinceLastPause = 0
                Thread.sleep(pauseMs)
            }
        }
        return body.toString(Charsets.ISO_8859_1)
    }

    private fun readBody(input: InputStream) {
        val body = StringBuilder()
        while (!body.endsWith(LAST_CHUNK)) {
            val byte = input.read()
            check(byte >= 0) { "EOF before the last chunk: $body" }
            body.append(byte.toChar())
        }
        assertTrue("[DONE]" in body) { body.toString() }
    }

    private companion object {
        const val BODY = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        const val REQUEST =
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${BODY.length}\r\n\r\n$BODY"
        const val LAST_CHUNK = "0\r\n\r\n"
        const val SEND_BUFFER = 16 * 1024
        const val RECEIVE_BUFFER = 4096
        const val READ_TIMEOUT_MS = 10_000
        const val READ_SIZE = 4096
        const val FRAME_TAIL = 200
        const val FRAMES = 200
        const val SLOW_PROVIDER_TIMEOUTS = 3L
        const val LARGE_FRAME = 1024 * 1024
        const val BURST = 48 * 1024
        const val BURST_PAUSE_MS = 500L
        const val BURST_PACE_MS = 6000L
        val TRICKLE: Duration = Duration.ofMillis(200)
        val PADDING = "x".repeat(16 * 1024)
        val TIMEOUT: Duration = Duration.ofSeconds(1)
        val WAIT: Duration = Duration.ofSeconds(10)
    }
}
