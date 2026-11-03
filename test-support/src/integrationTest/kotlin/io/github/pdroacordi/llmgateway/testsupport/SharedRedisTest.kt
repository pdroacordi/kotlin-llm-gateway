package io.github.pdroacordi.llmgateway.testsupport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets

class SharedRedisTest {
    @Test
    fun `PING over a raw socket to the mapped port gets PONG`() {
        assertEquals(listOf("+PONG"), send("PING"))
    }

    @Test
    fun `a value set under a test-class key prefix is read back`() {
        val key = "SharedRedisTest:greeting"

        assertEquals(listOf("+OK"), send("SET $key hello"))
        assertEquals(listOf("$5", "hello"), send("GET $key", replyLines = 2))
    }

    @Test
    fun `the uri names the mapped port`() {
        val uri = URI.create(SharedRedis.uri)
        assertEquals("redis", uri.scheme)
        assertEquals(SharedRedis.container.getMappedPort(6379), uri.port)
        assertTrue(uri.host.isNotBlank())
    }

    // Inline commands (RESP), one per call; enough for a fixture check without a client library.
    private fun send(
        command: String,
        replyLines: Int = 1,
    ): List<String> {
        val uri = URI.create(SharedRedis.uri)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("$command\r\n".toByteArray(StandardCharsets.UTF_8))
                flush()
            }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            List(replyLines) { reader.readLine() }
        }
    }
}
