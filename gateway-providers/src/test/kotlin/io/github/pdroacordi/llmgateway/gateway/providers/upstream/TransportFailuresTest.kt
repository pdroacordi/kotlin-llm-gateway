package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.netty.channel.ConnectTimeoutException
import io.netty.handler.codec.http.TooLongHttpHeaderException
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Named.named
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.PrematureCloseException
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class TransportFailuresTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `each transport exception maps to its failure`(
        exception: Throwable,
        expected: ProviderFailure,
    ) {
        assertEquals(expected, TransportFailures.classify(exception))
    }

    @Test
    fun `an unknown exception is a bug and is rethrown`() {
        val bug = IllegalStateException("a bug")

        assertSame(bug, assertThrows<IllegalStateException> { TransportFailures.classify(bug) })
    }

    @Test
    fun `a CancellationException is rethrown`() {
        val cancelled = CancellationException("the caller left")

        assertSame(cancelled, assertThrows<CancellationException> { TransportFailures.classify(cancelled) })
    }

    companion object {
        private val URL = URI.create("http://127.0.0.1:1/v1/chat/completions")

        private fun requestFailure(cause: Throwable) =
            WebClientRequestException(cause, HttpMethod.POST, URL, HttpHeaders())

        // A pool acquire timeout is a TimeoutException too, but WebClient wraps it, which is what tells it from ours.
        @JvmStatic
        fun cases(): List<Arguments> = nothingSent() + sentOrUnknown()

        private fun nothingSent(): List<Arguments> =
            listOf(
                "connection refused" to ConnectException("refused"),
                "connect timeout" to ConnectTimeoutException("timed out"),
                "no route to host" to NoRouteToHostException("unreachable"),
                "DNS failure" to UnknownHostException("no such host"),
                "TLS handshake failure" to SSLHandshakeException("handshake"),
                "pool acquire timeout" to PoolAcquireTimeoutException(Duration.ofSeconds(5)),
                "pool queue full" to PoolAcquirePendingLimitException(1),
                "wrapped deeper in the chain" to IOException("wrapped", ConnectException("refused")),
            ).map { (name, cause) ->
                Arguments.of(named(name, requestFailure(cause)), ProviderFailure.ProviderUnavailable(null))
            }

        // A fallback must not repeat a call the provider may have billed, so anything else is not "unavailable".
        private fun sentOrUnknown(): List<Arguments> =
            listOf(
                "our deadline" to TimeoutException(),
                "connection closed before the headers" to requestFailure(PrematureCloseException.TEST_EXCEPTION),
                "reset after the write" to requestFailure(SocketException("Connection reset")),
                "TLS failure after the handshake" to requestFailure(SSLException("bad record")),
                "not HTTP" to requestFailure(IllegalArgumentException("invalid version format")),
                "headers over the limit" to requestFailure(TooLongHttpHeaderException("HTTP header is larger")),
                "a request-side bug in the client" to requestFailure(IllegalStateException("anything else")),
                "premature close on the body" to PrematureCloseException.TEST_EXCEPTION,
                "a read error" to IOException("reset"),
                "response over the limit" to DataBufferLimitException("too big"),
                "a decoding error" to DecodingException("bad"),
                "a response exception" to WebClientResponseException(500, "x", null, null, null),
            ).map { (name, failure) ->
                val expected =
                    if (failure is TimeoutException) ProviderFailure.UpstreamTimeout else ProviderFailure.UpstreamError
                Arguments.of(named(name, failure), expected)
            }
    }
}
