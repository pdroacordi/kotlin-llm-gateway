package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.reactor.netty.NettyServerCustomizer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.net.Socket
import java.time.Duration

/**
 * A closeFuture listener runs after the channel went inactive and before channelInactive, so releasing the first frame
 * from one lands it in the window where `ChannelOperations.send` fails at once, every time
 * (docs/knowledge/bugs/first-frame-on-a-closing-channel-leaves-the-stream-flow-running.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FakeChatModelPort.Wiring::class, ChatStreamClosingChannelTest.Gate::class)
@ExtendWith(OutputCaptureExtension::class)
class ChatStreamClosingChannelTest(
    @Autowired private val provider: FakeChatModelPort,
    @Autowired private val closed: CompletableDeferred<Unit>,
    @LocalServerPort private val port: Int,
) {
    @TestConfiguration(proxyBeanMethods = false)
    class Gate {
        @Bean
        fun closed(): CompletableDeferred<Unit> = CompletableDeferred()

        @Bean
        fun releaseOnClose(closed: CompletableDeferred<Unit>): NettyServerCustomizer =
            NettyServerCustomizer { http ->
                http.doOnConnection { connection ->
                    connection.channel().closeFuture().addListener { closed.complete(Unit) }
                }
            }
    }

    @Test
    fun `a first frame on a closing channel still ends the flow, with one Stream cancelled line`(
        output: CapturedOutput,
    ) {
        provider.reset()
        provider.streamAnswer =
            {
                flow<ProviderStreamEvent> {
                    closed.await()
                    emit(FakeChatModelPort.chunk("Hi"))
                    emit(FakeChatModelPort.chunk(" there"))
                    emit(ProviderStreamEvent.Completed)
                }
            }

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(REQUEST.encodeToByteArray())
            await().atMost(WAIT).until { provider.calls.isNotEmpty() }
        }

        await().atMost(WAIT).until { "Stream cancelled" in output.all }
        val line = output.all.lines().single { "Stream cancelled" in it }
        assertTrue("outcome=cancelled" in line) { line }
        assertEquals(1, provider.calls.size)
    }

    private companion object {
        const val BODY = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],"stream":true}"""
        const val REQUEST =
            "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${BODY.length}\r\n\r\n$BODY"
        val WAIT: Duration = Duration.ofSeconds(5)
    }
}
