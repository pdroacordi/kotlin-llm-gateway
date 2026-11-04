package io.github.pdroacordi.llmgateway.gateway.app.security

import io.github.pdroacordi.llmgateway.gateway.app.chat.FakeChatModelPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.Socket

/**
 * What a client gets for a `Host` value, through a socket: Reactor Netty refuses some values before any `WebFilter`
 * (docs/knowledge/gotchas/reactor-netty-refuses-an-unparsable-host-before-webflux.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class)
class LoopbackHostSocketTest(
    @Autowired private val provider: FakeChatModelPort,
    @LocalServerPort private val port: Int,
) {
    private class Reply(
        val status: Int,
        val requestId: String?,
        val body: String,
    )

    @BeforeEach
    fun reset() {
        provider.reset()
    }

    private fun send(vararg hosts: String): Reply {
        val hostLines = hosts.joinToString("") { "Host: $it\r\n" }
        val request =
            "POST /v1/chat/completions HTTP/1.1\r\n${hostLines}Content-Type: application/json\r\n" +
                "Content-Length: ${BODY.length}\r\nConnection: close\r\n\r\n$BODY"
        val raw =
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.getOutputStream().write(request.encodeToByteArray())
                socket.getInputStream().readAllBytes().toString(Charsets.ISO_8859_1)
            }
        val head = raw.substringBefore("\r\n\r\n")
        return Reply(
            status = head.substringAfter(' ').substringBefore(' ').toInt(),
            requestId = Regex("(?im)^x-request-id: (\\S+)").find(head)?.groupValues?.get(1),
            body = raw.substringAfter("\r\n\r\n"),
        )
    }

    @Test
    fun `a loopback Host reaches the app`() {
        val reply = send("127.0.0.1")

        assertEquals(200, reply.status) { reply.body }
        assertNotNull(reply.requestId)
        assertEquals(1, provider.calls.size)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "[::1",
            "[::1].evil.example",
            "[::1]evil.example",
            "127.0.0.1:evil",
            "127.0.0.1:80@evil.example",
            "localhost:",
        ],
    )
    fun `a Host Reactor Netty cannot parse gets its empty 400, before any filter or the app`(host: String) {
        val reply = send(host)

        assertEquals(400, reply.status) { reply.body }
        assertNull(reply.requestId)
        assertEquals("", reply.body)
        assertTrue(provider.calls.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["127.0.0.1:123456", "[localhost]"])
    fun `a malformed Host Reactor Netty can parse gets the filter's 403`(host: String) {
        assertRefusedByFilter(send(host))
    }

    @ParameterizedTest
    @CsvSource("127.0.0.1, evil.example", "evil.example, 127.0.0.1")
    fun `two Host lines get the filter's 403, in either order`(
        first: String,
        second: String,
    ) {
        assertRefusedByFilter(send(first, second))
    }

    private fun assertRefusedByFilter(reply: Reply) {
        assertEquals(403, reply.status) { reply.body }
        assertNotNull(reply.requestId)
        assertTrue(reply.body.startsWith("""{"error":""")) { reply.body }
        assertTrue(provider.calls.isEmpty())
    }

    private companion object {
        const val BODY = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}]}"""
        const val READ_TIMEOUT_MS = 10_000
    }
}
