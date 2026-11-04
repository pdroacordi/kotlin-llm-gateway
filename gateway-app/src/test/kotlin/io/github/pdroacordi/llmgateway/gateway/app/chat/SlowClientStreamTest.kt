package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * Loopback buffers can absorb a whole finite answer, so an endless flow of 16 KB chunks against a socket that reads
 * nothing is what shows demand reaching the producer (ADR-GW-02-012). A plateau and a ceiling, never an exact figure.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class)
class SlowClientStreamTest(
    @Autowired private val provider: FakeChatModelPort,
    @LocalServerPort private val port: Int,
) {
    private val emitted = AtomicLong()

    private fun endless(): Flow<ProviderStreamEvent> =
        flow {
            while (true) emit(FakeChatModelPort.chunk(PADDING, id = "seq-${emitted.getAndIncrement()}"))
        }

    @Test
    fun `a client that stops reading stops the producer under the ceiling, and loses nothing when it reads again`() {
        provider.streamAnswer = { endless() }
        Socket().use { socket ->
            socket.receiveBufferSize = RECEIVE_BUFFER
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress("127.0.0.1", port))
            socket.getOutputStream().write(REQUEST.encodeToByteArray())

            val plateau = awaitPlateau()

            assertTrue(plateau < CEILING) { "$plateau frames emitted to a client that reads nothing" }
            val input = BufferedInputStream(socket.getInputStream())
            skipHead(input)
            val target = plateau + MORE_FRAMES
            var expected = 0L
            while (expected < target) {
                val sequence = nextSequence(input)
                assertEquals(expected, sequence)
                expected++
            }
            assertTrue(emitted.get() > plateau) { "the producer did not resume" }
        }
    }

    private fun awaitPlateau(): Long {
        var last = -1L
        await()
            .atMost(Duration.ofSeconds(20))
            .pollInterval(STABLE)
            .until {
                val now = emitted.get()
                val stable = now == last && now > 0
                last = now
                stable
            }
        return last
    }

    private fun skipHead(input: BufferedInputStream) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
        assertTrue(head.startsWith("HTTP/1.1 200")) { head.toString() }
    }

    // Each HTTP chunk holds one frame: one write and one flush per event.
    private fun nextSequence(input: BufferedInputStream): Long {
        val size = readLine(input).toInt(radix = 16)
        val frame = input.readNBytes(size).decodeToString()
        readLine(input)
        val found = Regex(""""id":"seq-(\d+)"""").find(frame)
        return checkNotNull(found) { frame.take(FRAME_HEAD) }.groupValues[1].toLong()
    }

    private fun readLine(input: BufferedInputStream): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            check(byte >= 0) { "EOF in the middle of the body" }
            if (byte == LF) return line.toString().trimEnd('\r')
            line.write(byte)
        }
    }

    private companion object {
        const val ROUTE = "/v1/chat/completions"
        const val BODY = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        const val REQUEST =
            "POST $ROUTE HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Authorization: ${TestKeys.BEARER}\r\nContent-Length: ${BODY.length}\r\n\r\n$BODY"
        const val LF = 10
        const val RECEIVE_BUFFER = 4096
        const val READ_TIMEOUT_MS = 10_000
        const val FRAME_HEAD = 200

        // GW-02 section 3: under 4096 frames of 16 KB.
        const val CEILING = 4096L
        const val MORE_FRAMES = 200L
        val PADDING = "x".repeat(16 * 1024)
        val STABLE: Duration = Duration.ofMillis(750)
    }
}
