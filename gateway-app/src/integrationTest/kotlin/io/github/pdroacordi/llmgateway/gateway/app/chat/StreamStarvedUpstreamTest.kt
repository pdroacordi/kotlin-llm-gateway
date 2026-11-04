package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.netty.channel.ChannelOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration

/**
 * The corner of GW-02 section 5, Back-pressure, pinned: `Flux.timeout` cannot see demand, so a live client too slow to
 * pull an upstream read within `idle-timeout` gets `upstream_timeout` from a healthy provider, which RTE-02 must not
 * count.
 */
@TestPropertySource(
    properties = [
        "llmgw.providers.openai.stream.idle-timeout=2s",
        "llmgw.server.client-write-timeout=1s",
    ],
)
@Import(StreamStarvedUpstreamTest.SmallSendBuffer::class)
class StreamStarvedUpstreamTest : MockBackedTest() {
    // A socket reports write progress only as its buffer drains, in steps that scale with its size.
    @TestConfiguration(proxyBeanMethods = false)
    class SmallSendBuffer {
        @Bean
        fun boundedSendBuffer(): NettyServerCustomizer =
            NettyServerCustomizer { http -> http.childOption(ChannelOption.SO_SNDBUF, SEND_BUFFER) }
    }

    @Test
    fun `a reader too slow to pull upstream reads gets upstream_timeout, and is not cut as a stalled client`() {
        val pieces = (" " + "x".repeat(PIECE)).repeat(PIECES)
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"$pieces"}}}""")

        val stream =
            Socket().use { socket ->
                socket.receiveBufferSize = RECEIVE_BUFFER
                socket.soTimeout = SOCKET_TIMEOUT_MS
                socket.connect(InetSocketAddress("127.0.0.1", port))
                socket.getOutputStream().write(rawRequest(STREAM, "Connection: close\r\n").encodeToByteArray())
                RawStream.of(readPaced(socket.getInputStream()))
            }

        assertEquals(200, stream.status)
        assertFalse("[DONE]" in stream.body)
        val last = mapper.readTree(stream.frames.last().removePrefix("data: "))
        assertEquals("upstream_timeout", last.at("/error/code").stringValue())
    }

    // Slow for longer than idle-timeout, with no pause near the write timeout, then at full speed to the end.
    private fun readPaced(input: InputStream): ByteArray {
        val body = ByteArrayOutputStream()
        val paceEnd = System.nanoTime() + PACE.toNanos()
        while (System.nanoTime() < paceEnd) {
            body.write(input.readNBytes(READ_SIZE))
            Thread.sleep(READ_PAUSE_MS)
        }
        input.transferTo(body)
        return body.toByteArray()
    }

    private companion object {
        const val PIECE = 16_000
        const val PIECES = 400
        const val SEND_BUFFER = 16 * 1024
        const val RECEIVE_BUFFER = 4096
        const val READ_SIZE = 16 * 1024
        const val READ_PAUSE_MS = 100L
        val PACE: Duration = Duration.ofSeconds(4)
    }
}
