package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Flux
import reactor.netty.internal.shaded.reactor.pool.PoolAcquirePendingLimitException
import reactor.netty.internal.shaded.reactor.pool.PoolAcquireTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLHandshakeException

// Guards against a cause cycle longer than one link, which Throwable.cause does not prevent.
private const val MAX_CAUSE_DEPTH = 32

/**
 * Any error from a response's body: nothing of ours runs above it, so it is the transport's whatever its type, and
 * Netty raises bad chunked framing as bare runtime exceptions. [source] is the class name, for the log.
 */
internal class UpstreamBodyFailure(
    val source: String,
) : IOException() {
    override fun fillInStackTrace(): Throwable = this
}

internal fun Flux<DataBuffer>.bodyFailuresAsTransport(): Flux<DataBuffer> =
    onErrorMap { UpstreamBodyFailure(it.javaClass.name) }

/** The class a log line should name. */
internal fun Throwable.sourceName(): String = (this as? UpstreamBodyFailure)?.source ?: javaClass.name

/** What a call raised to a [ProviderFailure], in the order of GW-01 section 5; anything else is a bug and rethrows. */
internal object OpenAiTransportFailures {
    fun classify(failure: Throwable): ProviderFailure =
        when (failure) {
            // Only the top level is our Mono.timeout: WebClient wraps a pool acquire timeout.
            is TimeoutException -> {
                ProviderFailure.UpstreamTimeout
            }

            is WebClientRequestException -> {
                if (failure.nothingWasSent()) {
                    ProviderFailure.ProviderUnavailable(null)
                } else {
                    ProviderFailure.UpstreamError
                }
            }

            is IOException, is DataBufferLimitException, is DecodingException, is WebClientResponseException -> {
                ProviderFailure.UpstreamError
            }

            else -> {
                throw failure
            }
        }

    // The provider cannot have seen the call, so RTE-02 may repeat it. Anything else may have reached it, and billed.
    private fun Throwable.nothingWasSent(): Boolean =
        generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { cause ->
            when (cause) {
                is ConnectException,
                is NoRouteToHostException,
                is UnknownHostException,
                is SSLHandshakeException,
                is PoolAcquireTimeoutException,
                is PoolAcquirePendingLimitException,
                -> true

                else -> false
            }
        }
}
