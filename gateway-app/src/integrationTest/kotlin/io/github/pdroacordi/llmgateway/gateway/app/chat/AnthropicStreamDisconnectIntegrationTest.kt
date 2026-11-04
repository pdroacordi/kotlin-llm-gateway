package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket

class AnthropicStreamDisconnectIntegrationTest : AnthropicBackedTest() {
    @Test
    fun `a client that closes after the first frame leaves client_cancelled within 2 s`() {
        val words = "word ".repeat(WORDS)
        claudeScenario("""{"otherwise":{"latency":{"perTokenMs":50},"content":{"mode":"fixed","text":"$words"}}}""")

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(rawRequest(CLAUDE_STREAM).encodeToByteArray())
            socket.getInputStream().read()
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
        assertTrue(journal().single().at("/outcome/piecesSent").intValue() < WORDS)
    }

    @Test
    fun `a client that closes during a hang cancels the upstream call within 2 s`() {
        claudeScenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val before = lastSeq()

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(rawRequest(CLAUDE_STREAM).encodeToByteArray())
            eventually { lastSeq() > before }
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }

    private companion object {
        const val WORDS = 200
    }
}
