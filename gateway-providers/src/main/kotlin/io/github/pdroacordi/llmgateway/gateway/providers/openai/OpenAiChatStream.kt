package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.reactive.function.BodyExtractors
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.util.function.Function

/**
 * The stream half of the adapter: one POST, whose bytes go chunk by chunk through [OpenAiStreamTranslator], and one
 * `Flux.timeout` for every deadline (GW-02 section 5). Nothing here is a Flow until the bytes are plain objects.
 */
internal class OpenAiChatStream(
    private val client: WebClient,
    private val endpoint: URI,
    private val config: OpenAiAdapterConfig,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    private val trees = mapper.exactTreeReader()
    private val reader = OpenAiChunkReader(trees)
    private val classifier = OpenAiFailureClassifier(trees)

    fun stream(call: ChatCall): Flow<ProviderStreamEvent> =
        flow {
            val started = clock.millis()
            val translator = OpenAiStreamTranslator(reader, classifier, config.stream.maxEventBytes)
            val deadlines = StreamDeadlines(config.stream, clock, started)
            val reply = UpstreamReply()
            wire(call, translator, deadlines, reply)
                .asFlow()
                .buffer(STREAM_BUFFER)
                .map { it.events }
                // The operator, not try/catch around collect: that would also catch what downstream throws, which
                // breaks Flow's exception transparency. classify rethrows anything unnamed, so a bug stays a bug.
                .catch { cause ->
                    val failure = OpenAiTransportFailures.classify(cause)
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
        call: ChatCall,
        translator: OpenAiStreamTranslator,
        deadlines: StreamDeadlines,
        reply: UpstreamReply,
    ): Flux<Batch> {
        val body = mapper.writeValueAsBytes(OpenAiRequestWriter.writeStream(call, mapper.nodeFactory))
        return client
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .headers {
                it.setBearerAuth(config.apiKey.reveal())
                it.set(CLIENT_REQUEST_ID, call.requestId.toString())
            }.bodyValue(body)
            .exchangeToFlux { response ->
                reply.status = response.statusCode().value()
                reply.providerRequestId = providerRequestId(response.headers().asHttpHeaders())
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
    }

    private fun copyAndRelease(buffer: DataBuffer): ByteArray =
        try {
            ByteArray(buffer.readableByteCount()).also { buffer.read(it) }
        } finally {
            DataBufferUtils.release(buffer)
        }

    private fun refused(
        response: ResponseEntity<ByteArray>,
        translator: OpenAiStreamTranslator,
        reply: UpstreamReply,
    ): Batch {
        val body = response.body ?: ByteArray(0)
        reply.errorFields = classifier.errorFields(body)
        val status = response.statusCode.value()
        return Batch(translator.fail(classifier.classify(status, response.headers, body, clock.instant())))
    }

    private fun logFailure(
        call: ChatCall,
        reply: UpstreamReply,
        translator: OpenAiStreamTranslator,
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
        private val log = LoggerFactory.getLogger(OpenAiChatStream::class.java)
    }
}
