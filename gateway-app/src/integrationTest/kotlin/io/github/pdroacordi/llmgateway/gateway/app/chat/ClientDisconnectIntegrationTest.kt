package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Test
import java.net.Socket

class ClientDisconnectIntegrationTest : MockBackedTest() {
    // A raw socket: closing it is a client that goes away, with no HTTP client to soften it.
    @Test
    fun `a client that disconnects during a hang leaves client_cancelled at the mock within 2 s`() {
        scenario("""{"otherwise":{"fault":{"kind":"hang"}}}""")
        val before = lastSeq()

        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(rawRequest(MINIMAL).encodeToByteArray())
                flush()
            }
            eventually { lastSeq() > before }
        }

        eventually { journal().singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled" }
    }
}
