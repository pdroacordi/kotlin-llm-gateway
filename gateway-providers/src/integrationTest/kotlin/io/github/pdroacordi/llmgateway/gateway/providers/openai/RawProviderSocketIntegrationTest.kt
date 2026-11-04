package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.MessageContent
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.Role
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** What the mock cannot send: a provider that answers one bad reply per connection, over a raw socket. */
class RawProviderSocketIntegrationTest {
    private class RawProvider(
        answer: (Socket) -> Unit,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val connections = AtomicInteger()
        val port: Int = server.localPort

        private val acceptor =
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    connections.incrementAndGet()
                    socket.readRequest()
                    answer(socket)
                }
            }

        private fun Socket.readRequest() {
            val stream = getInputStream()
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(stream.read().toChar())
            val length =
                Regex("(?i)content-length: *(\\d+)")
                    .find(head)
                    ?.groupValues
                    ?.get(1)
                    ?.toInt() ?: 0
            stream.readNBytes(length)
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

    private fun provider(answer: (Socket) -> Unit): RawProvider = RawProvider(answer).also { closeables += it }

    private fun complete(provider: RawProvider): ProviderResult {
        val adapter =
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
                ),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
            ).also { closeables += it }
        return runBlocking { adapter.complete(CALL) }
    }

    private fun Socket.reply(
        status: String,
        headers: String,
        body: String = "",
    ) {
        val bytes = body.encodeToByteArray()
        val head = "HTTP/1.1 $status\r\n$headers\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        getOutputStream().apply {
            write(head.encodeToByteArray())
            write(bytes)
            flush()
        }
        close()
    }

    @ParameterizedTest
    @ValueSource(strings = [GARBAGE, EMPTY_CHARSET, UNKNOWN_CHARSET])
    fun `a 200 with a Content-Type that cannot be parsed still returns its completion`(contentType: String) {
        val provider = provider { it.reply("200 OK", "Content-Type: $contentType", COMPLETION) }

        val result = complete(provider)

        assertEquals(
            "Hi there",
            (result as ProviderResult.Completed)
                .completion.choices
                .single()
                .message.content,
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [GARBAGE, EMPTY_CHARSET, UNKNOWN_CHARSET])
    fun `a 500 with a Content-Type that cannot be parsed maps by status`(contentType: String) {
        val provider =
            provider { it.reply("500 Internal Server Error", "Content-Type: $contentType", "<html>x</html>") }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete(provider))
    }

    @Test
    fun `a 429 with a Content-Type that cannot be parsed keeps its code and wait`() {
        val body = """{"error":{"message":"m","type":"x","param":null,"code":"insufficient_quota"}}"""
        val quota = provider { it.reply("429 Too Many Requests", "Content-Type: $GARBAGE", body) }
        val limited = provider { it.reply("429 Too Many Requests", "Content-Type: $GARBAGE\r\nRetry-After: 7", "") }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamQuotaExhausted), complete(quota))
        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamRateLimited(7.seconds)), complete(limited))
    }

    // SO_LINGER 0 makes close send RST. The provider read the request, so a fallback must not repeat it.
    @Test
    fun `a reset after the request was written is UpstreamError, and is not retried`() {
        val provider =
            provider {
                it.setSoLinger(true, 0)
                it.close()
            }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete(provider))
        assertEquals(1, provider.connections.get())
    }

    @Test
    fun `a close after the request was written is UpstreamError, and is not retried`() {
        val provider = provider { it.close() }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete(provider))
        assertEquals(1, provider.connections.get())
    }

    @Test
    fun `a reply that is not HTTP is UpstreamError`() {
        val provider =
            provider {
                it.getOutputStream().write("SSH-2.0-OpenSSH_9.6\r\n\r\n".encodeToByteArray())
                it.close()
            }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete(provider))
    }

    @Test
    fun `response headers over the 8 KB limit are UpstreamError`() {
        val provider = provider { it.reply("200 OK", "X-Large: ${"a".repeat(9_000)}", COMPLETION) }

        assertEquals(ProviderResult.Failed(ProviderFailure.UpstreamError), complete(provider))
        assertTrue(provider.connections.get() >= 1)
    }

    private companion object {
        const val GARBAGE = "garbage"
        const val EMPTY_CHARSET = "text/html; charset="
        const val UNKNOWN_CHARSET = "text/html; charset=no-such-charset"
        const val COMPLETION =
            """{"id":"chatcmpl-1","object":"chat.completion","created":1793750400,"model":"m",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Hi there"},"finish_reason":"stop"}]}"""
        val CALL =
            ChatCall(
                RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("m")),
                ChatRequest(ModelName("m"), listOf(ChatMessage(Role("user"), MessageContent.Text("Hi")))),
            )
    }
}
