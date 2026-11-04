package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource
import java.io.InputStream
import java.net.Socket

/** Stream completed is a DEBUG line; the race needs to see it. */
@TestPropertySource(properties = ["logging.level.io.github.pdroacordi.llmgateway.gateway.app.chat=DEBUG"])
@ExtendWith(OutputCaptureExtension::class)
class StreamDisconnectIntegrationTest : MockBackedTest() {
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    /** Reads until [marker] was seen, then closes: SO_LINGER 0 makes the close a reset. Returns the request ID. */
    private fun closingAt(
        marker: String,
        reset: Boolean,
    ): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(rawRequest().encodeToByteArray())
            val seen = readUntil(socket.getInputStream(), marker)
            if (reset) socket.setSoLinger(true, 0)
            return checkNotNull(Regex("(?i)x-request-id: (\\S+)").find(seen)).groupValues[1]
        }
    }

    private fun readUntil(
        input: InputStream,
        marker: String,
    ): String {
        val seen = StringBuilder()
        val buffer = ByteArray(READ_SIZE)
        while (marker !in seen) {
            val read = input.read(buffer)
            check(read >= 0) { "EOF before $marker: $seen" }
            seen.append(String(buffer, 0, read, Charsets.ISO_8859_1))
        }
        return seen.toString()
    }

    private fun endLines(
        requestId: String,
        output: CapturedOutput,
    ): List<String> =
        output.all.lines().filter { requestId in it && ("Stream completed" in it || "Stream cancelled" in it) }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a client that closes after the first frame leaves client_cancelled and one INFO line`(
        reset: Boolean,
        output: CapturedOutput,
    ) {
        val words = "word ".repeat(WORDS)
        scenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$words"}}}""")

        val requestId = closingAt("data: ", reset)

        eventually {
            journalOf(requestId).singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled"
        }
        assertTrue(journalOf(requestId).single().at("/outcome/piecesSent").intValue() < WORDS)
        eventually { endLines(requestId, output).isNotEmpty() }
        val line = endLines(requestId, output).single()
        assertTrue(" INFO " in line && "Stream cancelled" in line) { line }
        assertFalse(output.all.lines().any { " ERROR " in it && requestId in it }) { output.all }
    }

    @Test
    fun `a client that closes before the first frame cancels the upstream call, with one INFO line`(
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val before = lastSeq()

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(rawRequest().encodeToByteArray())
            eventually { lastSeq() > before }
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
        val requestId = journal().single().at("/headers/x-client-request-id/0").stringValue()
        eventually { endLines(requestId, output).isNotEmpty() }
        val line = endLines(requestId, output).single()
        assertTrue(" INFO " in line && "Stream cancelled" in line && "chunks=0" in line) { line }
    }

    @Test
    fun `closing as the last frames arrive gives exactly one end line per request, and no ERROR`(
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val requestIds = (0 until ROUNDS).map { closingAt(FINISH_MARKER, reset = it % 2 == 0) }

        requestIds.forEach { requestId ->
            eventually { endLines(requestId, output).isNotEmpty() }
            val line = endLines(requestId, output).single()
            assertTrue("outcome=completed" in line || "outcome=cancelled" in line) { line }
        }
        // Every ERROR of this test: Reactor Netty's "Error finishing response" carries no request ID.
        val log = output.all.substring(mark)
        assertFalse(log.lines().any { it.trimStart() == it && " ERROR " in it }) { log }
    }

    private companion object {
        const val FINISH_MARKER = """"finish_reason":"stop""""
        const val WORDS = 200
        const val ROUNDS = 50
        const val READ_SIZE = 4096
    }
}

@TestPropertySource(properties = ["llmgw.providers.openai.pool.max-connections=2"])
class StreamPoolRecoveryTest : MockBackedTest() {
    @Test
    fun `with 2 connections, 20 cancelled streams in a row leave a pool that serves the next stream`() {
        val words = "word ".repeat(WORDS)
        scenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$words"}}}""")

        repeat(CANCELLED) {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.getOutputStream().write(rawRequest().encodeToByteArray())
                socket.getInputStream().read()
            }
        }
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi"}}}""")

        val stream = rawStream()

        assertEquals(200, stream.status)
        assertEquals("data: [DONE]", stream.frames.last())
    }

    private companion object {
        const val WORDS = 200
        const val CANCELLED = 20
    }
}
