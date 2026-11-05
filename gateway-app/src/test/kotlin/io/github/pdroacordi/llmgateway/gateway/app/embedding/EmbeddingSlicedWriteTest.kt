package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.embedding.RawJson
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * [LargestWrite] sits at the head of the pipeline, next to the socket: the size it sees is what Netty copies to a
 * direct buffer in one go (docs/knowledge/gotchas/netty-copies-a-heap-buffer-to-direct-on-write.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeEmbeddingModelPort.Wiring::class, EmbeddingSlicedWriteTest.Wiring::class)
@Timeout(60)
class EmbeddingSlicedWriteTest(
    @LocalServerPort private val port: Int,
    @Autowired private val provider: FakeEmbeddingModelPort,
) {
    @ChannelHandler.Sharable
    class LargestWrite : ChannelOutboundHandlerAdapter() {
        val bytes = AtomicInteger()
        val written = AtomicLong()
        val peakHeld = AtomicLong()

        override fun write(
            ctx: ChannelHandlerContext,
            msg: Any,
            promise: ChannelPromise,
        ) {
            if (msg is ByteBuf) {
                bytes.accumulateAndGet(msg.readableBytes(), ::maxOf)
                written.addAndGet(msg.readableBytes().toLong())
            }
            super.write(ctx, msg, promise)
            // What waits for the socket: each of these bytes has been copied to direct memory.
            ctx.channel().unsafe().outboundBuffer()?.let {
                peakHeld.accumulateAndGet(it.totalPendingWriteBytes(), ::maxOf)
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class Wiring {
        @Bean
        fun largestWriteCustomizer(): NettyServerCustomizer =
            NettyServerCustomizer { server ->
                server.doOnChannelInit { _, channel, _ -> channel.pipeline().addFirst(LARGEST) }
            }
    }

    @Test
    fun `a large reply reaches the socket in slices, with Content-Length and every byte in place`() {
        val vector = "1".repeat(REPLY_BYTES)
        provider.answer = {
            val list = EmbeddingList(ModelName("m"), listOf(Embedding(0, RawJson(vector.encodeToByteArray()))))
            EmbeddingResult.Embedded(list, FakeEmbeddingModelPort.USAGE)
        }
        LARGEST.bytes.set(0)
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/v1/embeddings"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"mock-embedding","input":"a"}"""))
                .build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())

        assertEquals(200, response.statusCode())
        assertEquals(
            response.body().size.toString(),
            response.headers().firstValue("content-length").orElse(null),
        )
        val body = response.body().decodeToString()
        assertTrue(""""embedding":$vector}""" in body)
        assertTrue(LARGEST.bytes.get() in 1..LARGEST_ALLOWED) { "largest write: ${LARGEST.bytes.get()}" }
    }

    @Test
    fun `two large replies in a row on one connection both arrive whole`() {
        val vector = "1".repeat(REPLY_BYTES)
        provider.answer = {
            val list = EmbeddingList(ModelName("m"), listOf(Embedding(0, RawJson(vector.encodeToByteArray()))))
            EmbeddingResult.Embedded(list, FakeEmbeddingModelPort.USAGE)
        }
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val request =
            HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:$port/v1/embeddings"))
                .header("Authorization", TestKeys.BEARER)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("""{"model":"mock-embedding","input":"a"}"""))
                .build()

        repeat(2) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

            assertEquals(200, response.statusCode())
            assertTrue(response.body().size > REPLY_BYTES)
        }
    }

    @Test
    fun `a client that stops reading holds at most about one slice in the channel`() {
        provider.answer = { EmbeddingResult.Embedded(STALLED_LIST, FakeEmbeddingModelPort.USAGE) }
        LARGEST.written.set(0)
        LARGEST.peakHeld.set(0)
        val body = """{"model":"mock-embedding","input":"a"}"""
        val request =
            "POST /v1/embeddings HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: ${TestKeys.BEARER}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n$body"

        Socket().use { socket ->
            socket.receiveBufferSize = RECEIVE_WINDOW
            socket.soTimeout = STALL_WAIT.toMillis().toInt()
            socket.connect(InetSocketAddress("127.0.0.1", port))
            socket.getOutputStream().write(request.encodeToByteArray())
            var last = -1L
            await().atMost(STALL_WAIT).pollInterval(STALL_POLL).until {
                val now = LARGEST.written.get()
                (now > 0 && now == last).also { last = now }
            }
        }

        assertTrue(LARGEST.written.get() < STALLED_BYTES) { "the client read nothing: ${LARGEST.written.get()}" }
        assertTrue(LARGEST.peakHeld.get() <= HELD_ALLOWED) { "held ${LARGEST.peakHeld.get()} bytes" }
    }

    private companion object {
        const val SLICE = 1024 * 1024
        const val REPLY_BYTES = 5 * SLICE
        const val STALLED_BYTES = 32 * SLICE
        const val RECEIVE_WINDOW = 4096

        // The slice being written, and a head that can share the channel with it.
        const val HELD_ALLOWED = 2L * SLICE
        val STALL_WAIT: Duration = Duration.ofSeconds(20)
        val STALL_POLL: Duration = Duration.ofMillis(500)
        val STALLED_LIST =
            EmbeddingList(
                ModelName("m"),
                listOf(Embedding(0, RawJson(ByteArray(STALLED_BYTES) { '1'.code.toByte() }))),
            )

        // A head of status line and headers can share a buffer with the first slice.
        const val LARGEST_ALLOWED = SLICE + 8192
        val LARGEST = LargestWrite()
    }
}
