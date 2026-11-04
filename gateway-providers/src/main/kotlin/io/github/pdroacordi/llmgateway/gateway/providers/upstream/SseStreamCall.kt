package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.function.Function

/** The provider's reading of a stream refused with a status that is not 2xx. */
internal interface StreamRefusals {
    fun classify(
        status: Int,
        headers: HttpHeaders,
        body: ByteArray,
        now: Instant,
    ): ProviderFailure

    fun errorFields(body: ByteArray): ProviderErrorFields
}

/**
 * One streaming POST, whose bytes go chunk by chunk through a [StreamLifecycle], and one `Flux.timeout` for every
 * deadline (GW-02 section 5). Nothing here is a Flow until the bytes are plain objects.
 */
internal class SseStreamCall(
    private val client: WebClient,
    private val endpoint: URI,
    private val config: StreamConfig,
    private val refusals: StreamRefusals,
    private val requestIdHeader: String,
    private val clock: Clock,
) {
    /** [lifecycle] gives this stream its own instance; it and [body] run on collection. */
    fun stream(
        call: ChatCall,
        lifecycle: () -> StreamLifecycle,
        headers: (HttpHeaders) -> Unit,
        body: () -> ByteArray,
    ): Flow<ProviderStreamEvent> =
        flow {
            val started = clock.millis()
            val translator = lifecycle()
            val deadlines = StreamDeadlines(config, clock, started)
            val reply = UpstreamReply()
            wire(translator, deadlines, reply, headers, body())
                .asFlow()
                .buffer(STREAM_BUFFER)
                .map { it.events }
                // The operator, not try/catch around collect: that would also catch what downstream throws, which
                // breaks Flow's exception transparency. classify rethrows anything unnamed, so a bug stays a bug.
                .catch { cause ->
                    val failure = TransportFailures.classify(cause)
                    reply.exception = cause.sourceName()
                    emit(translator.fail(failure))
                }.collect { events ->
                    events.forEach { event ->
                        // Here, not in wire: only the coroutine carries MDC, so the line has the request ID.
                        if (event is ProviderStreamEvent.Failed) logFailure(call, reply, translator, started)
                        if (event !is ProviderStreamEvent.Chunk) translator.delivered()
                        emit(event)
                    }
                }
        }

    // One Flux.timeout gives first-byte, idle and total, on Reactor's parallel timer (ADR-GW-02-006). A Batch with no
    // events still resets it: any upstream byte proves the provider is alive.
    private fun wire(
        translator: StreamLifecycle,
        deadlines: StreamDeadlines,
        reply: UpstreamReply,
        headers: (HttpHeaders) -> Unit,
        body: ByteArray,
    ): Flux<Batch> =
        client
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers(headers)
            .bodyValue(body)
            .exchangeToFlux { response ->
                reply.status = response.statusCode().value()
                reply.providerRequestId = providerRequestId(response.headers().asHttpHeaders(), requestIdHeader)
                if (response.statusCode().is2xxSuccessful) {
                    // Copied and released here: asFlow's channel drops what it holds on cancel, and a retained buffer
                    // in it would leak (ADR-GW-02-008).
                    response
                        .body(BodyExtractors.toDataBuffers())
                        .bodyFailuresAsTransport()
                        .map(::copyAndRelease)
                        .map { Batch(translator.feed(it)) }
                } else {
                    withoutContentType(response)
                        .toEntity(ByteArray::class.java)
                        .map { refused(it, translator, reply) }
                        .flux()
                }
            }.concatWith(Flux.defer { Mono.just(Batch(translator.finish())) })
            .timeout(
                Mono.delay(deadlines.first()),
                Function { Mono.delay(deadlines.next(translator.draining)) },
            )
            // Cancels upstream on a failure. Draining reads on to EOF, so the connection returns to the pool.
            .takeUntil { translator.ended }

    private fun copyAndRelease(buffer: DataBuffer): ByteArray =
        try {
            ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
        } finally {
            DataBufferUtils.release(buffer)
        }

    private fun refused(
        response: ResponseEntity<ByteArray>,
        translator: StreamLifecycle,
        reply: UpstreamReply,
    ): Batch {
        val body = response.body ?: ByteArray(0)
        reply.errorFields = refusals.errorFields(body)
        val status = response.statusCode.value()
        return Batch(translator.fail(refusals.classify(status, response.headers, body, clock.instant())))
    }

    private fun logFailure(
        call: ChatCall,
        reply: UpstreamReply,
        translator: StreamLifecycle,
        started: Long,
    ) {
        val fields = translator.errorFields ?: reply.errorFields
        log.warn(
            "Provider stream failed: deployment={} status={} exception={} code={} type={} providerRequestId={} " +
                "durationMs={}",
            call.deployment.id.value,
            reply.status,
            reply.exception,
            fields?.code,
            fields?.type,
            reply.providerRequestId,
            clock.millis() - started,
        )
    }

    private class Batch(
        val events: List<ProviderStreamEvent>,
    )

    // Written by the Flux and read by the collector, after the batch or the error that hands it over.
    private class UpstreamReply {
        @Volatile
        var status: Int? = null

        @Volatile
        var providerRequestId: String? = null

        @Volatile
        var errorFields: ProviderErrorFields? = null

        @Volatile
        var exception: String? = null
    }

    private companion object {
        // The smallest that keeps reads batched; bounds the adapter's share of a stream at 8 reads (ADR-GW-02-009).
        const val STREAM_BUFFER = 8
        private val log = LoggerFactory.getLogger(SseStreamCall::class.java)
    }
}
