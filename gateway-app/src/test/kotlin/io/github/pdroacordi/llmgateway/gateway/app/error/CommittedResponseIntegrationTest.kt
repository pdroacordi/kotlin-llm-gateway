package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.app.ProbeController
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.Socket
import java.time.Duration

/**
 * A real socket, because Reactor Netty's own log line and connection close are what is under test
 * (docs/knowledge/gotchas/webflux-committed-error-reaches-netty-log-or-completes-normally.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ProbeController::class)
@ExtendWith(OutputCaptureExtension::class)
class CommittedResponseIntegrationTest(
    @LocalServerPort private val port: Int,
) {
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    private fun ownLog(output: CapturedOutput): String = output.all.substring(mark)

    @Test
    fun `an error after commit aborts the body and leaves no query in the log`(output: CapturedOutput) {
        val raw = rawGet("/test-probe/committed-chunk?$QUERY")

        assertAborted(raw)
        await().atMost(WAIT).until { NETTY_LINE in ownLog(output) }
        assertFalse(QUERY in output.all) { output.all }
        assertEquals(1, mapperErrorLines(output).size) { output.all }
    }

    @Test
    fun `an error quoting a disconnect after commit also aborts and logs one mapper ERROR line`(
        output: CapturedOutput,
    ) {
        val raw = rawGet("/test-probe/committed-chunk-disconnect-phrase?$QUERY")

        assertAborted(raw)
        await().atMost(WAIT).until { NETTY_LINE in ownLog(output) }
        assertFalse(QUERY in output.all) { output.all }
        assertEquals(1, mapperErrorLines(output).size) { output.all }
    }

    private fun mapperErrorLines(output: CapturedOutput): List<String> =
        ownLog(output).lines().filter { " ERROR " in it && "GatewayErrorWebExceptionHandler" in it }

    // A complete chunked body ends with the zero-length chunk; `Connection: close` makes the read end either way.
    private fun assertAborted(raw: String) {
        assertTrue(raw.startsWith("HTTP/1.1 200")) { raw }
        assertTrue("partial" in raw) { raw }
        assertFalse(raw.endsWith("0\r\n\r\n")) { raw }
    }

    private fun rawGet(target: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request = "GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().write(request.encodeToByteArray())
            socket.getInputStream().readAllBytes().decodeToString()
        }

    private companion object {
        const val QUERY = "secret-query=abc123"
        const val NETTY_LINE = "Error finishing response"
        const val SOCKET_TIMEOUT_MS = 5000
        val WAIT: Duration = Duration.ofSeconds(5)
    }
}
