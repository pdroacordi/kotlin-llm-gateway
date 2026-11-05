package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.Deployment
import io.github.pdroacordi.llmgateway.gateway.core.chat.DeploymentId
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderId
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.request.RequestId
import io.github.pdroacordi.llmgateway.gateway.core.team.TestKeys
import io.github.pdroacordi.llmgateway.gateway.providers.ApiKey
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.PoolConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Clock
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.uuid.Uuid

/** What the mock cannot send: its renderer normalises a number, so a literal needs a raw socket. */
class RawProviderEmbeddingIntegrationTest {
    private fun embed(reply: String): EmbeddingResult =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val answering =
                thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val stream = socket.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) head.append(stream.read().toChar())
                        val length =
                            Regex("(?i)content-length: *(\\d+)")
                                .find(head)
                                ?.groupValues
                                ?.get(1)
                                ?.toInt() ?: 0
                        stream.readNBytes(length)
                        val bytes = reply.encodeToByteArray()
                        val response =
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\n" +
                                "Content-Length: ${bytes.size}\r\n\r\n"
                        socket.getOutputStream().write(response.encodeToByteArray() + bytes)
                    }
                }
            val adapter =
                OpenAiEmbeddingAdapter(
                    OpenAiEmbeddingAdapterConfig(
                        baseUrl = URI.create("http://127.0.0.1:${server.localPort}/v1"),
                        apiKey = ApiKey("provider-key-not-real"),
                        connectTimeout = Duration.ofSeconds(5),
                        requestTimeout = Duration.ofSeconds(5),
                        maxResponseBytes = 1_048_576,
                        pool =
                            PoolConfig(
                                1,
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(30),
                                Duration.ofMinutes(5),
                            ),
                        cpuThreads = 1,
                        cpuQueueSize = 64,
                    ),
                    JsonMapper.builder().build(),
                    Clock.systemUTC(),
                )
            adapter.use {
                val call =
                    EmbeddingCall(
                        RequestId(Uuid.parse("01890a5d-ac96-774b-bcce-b302099a8057")),
                        TestKeys.TEAM_ID,
                        Deployment(DeploymentId("openai"), ProviderId("openai"), ModelName("m")),
                        EmbeddingInput.Text("a"),
                    )
                runBlocking { it.embed(call) }.also { answering.join(Duration.ofSeconds(5)) }
            }
        }

    @Test
    fun `each number literal and the whitespace inside the array survive a real reply`() {
        val vector = "[ 1e-5 ,\n  -0.10, 1E+2,0.1000000000000000055511151231257827 ]"

        val result = embed("""{"data":[{"index":0,"embedding":$vector}],"model":"m"}""") as EmbeddingResult.Embedded

        assertEquals(
            vector,
            result.list.data
                .single()
                .vector.bytes
                .decodeToString(),
        )
    }

    @Test
    fun `a member past BigDecimal's range in a vector does not fail the call`() {
        val result = embed("""{"data":[{"index":0,"embedding":[1e99999999999]}],"model":"m"}""")

        assertEquals(
            "[1e99999999999]",
            (result as EmbeddingResult.Embedded)
                .list.data
                .single()
                .vector.bytes
                .decodeToString(),
        )
    }
}
