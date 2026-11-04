package io.github.pdroacordi.llmgateway.gateway.app.security

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.core.Ordered
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

class LoopbackHostWebFilterTest {
    private fun chain(passed: (Boolean) -> Unit) =
        WebFilterChain {
            passed(true)
            Mono.empty()
        }

    private fun filter(
        vararg hosts: String,
        exposed: Boolean = false,
    ): Boolean {
        val request = MockServerHttpRequest.post("/v1/chat/completions")
        hosts.forEach { request.header("Host", it) }
        var passed = false
        LoopbackHostWebFilter(exposed)
            .filter(MockServerWebExchange.from(request), chain { passed = it })
            .block()
        return passed
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["127.0.0.1:8080", "127.0.0.1", "localhost:8080", "localhost", "LocalHost:1", "[::1]:8080", "[::1]"],
    )
    fun `a loopback Host passes`(host: String) {
        assertTrue(filter(host))
    }

    @Test
    fun `an absent Host passes`() {
        assertTrue(filter())
    }

    // The filter's own rule: over a socket Reactor Netty refuses several of these first (LoopbackHostSocketTest).
    @ParameterizedTest
    @ValueSource(
        strings = [
            "evil.example",
            "evil.example:8080",
            "127.0.0.1.evil.example",
            "localhost.evil.example",
            "192.168.1.10:8080",
            "[::2]:8080",
            "[::1",
            "",
            "[::1].evil.example",
            "[::1]evil.example",
            "127.0.0.1:evil",
            "127.0.0.1:80@evil.example",
            "localhost:evil",
            "localhost:",
            "127.0.0.1:123456",
            "[127.0.0.1]:8080",
            "[localhost]",
        ],
    )
    fun `any other Host is a 403`(host: String) {
        val failure = assertThrows<GatewayErrorException> { filter(host) }

        assertEquals(GatewayError.RejectedRequest(403), failure.error)
    }

    @Test
    fun `one bad value among several is a 403`() {
        assertThrows<GatewayErrorException> { filter("127.0.0.1", "evil.example") }
    }

    @Test
    fun `when exposure is allowed any Host passes`() {
        assertTrue(filter("evil.example", exposed = true))
    }

    @Test
    fun `it runs right after the request context filter, so its 403 has an x-request-id`() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, LoopbackHostWebFilter(false).order)
    }
}
