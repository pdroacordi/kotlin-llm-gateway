package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CLIENT_REQUEST_ID
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.Exchange
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.SseStreamCall
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamLifecycle
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.TransportFailures
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.UpstreamClient
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.providerRequestId
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.sourceName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.security.MessageDigest
import java.time.Clock

/**
 * The Anthropic side of [ChatModelPort]: translate, one POST, and pure functions for what comes back, chunk by chunk on
 * a stream. A refused request touches no socket, on both paths. No retries: RTE-02 owns them.
 */
class AnthropicChatAdapter(
    private val config: AnthropicAdapterConfig,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : ChatModelPort,
    AutoCloseable {
    private val trees = mapper.exactTreeReader()
    private val writer = AnthropicRequestWriter(mapper.nodeFactory, trees, config.defaultMaxTokens)
    private val reader = AnthropicResponseReader(trees, mapper, clock)
    private val classifier = AnthropicFailureClassifier(trees)
    private val endpoint = URI.create("${config.baseUrl.toString().trimEnd('/')}/messages")
    private val upstream = UpstreamClient(POOL_NAME, config.connectTimeout, config.maxResponseBytes, config.pool)
    private val streams = SseStreamCall(upstream.web, endpoint, config.stream, classifier, REQUEST_ID, clock)

    /** Startup only; blocks. The first request would otherwise load these on an event loop. */
    fun warmUp() {
        upstream.warmUp(mapper, trees)
        MessageDigest.getInstance(AnthropicRequestWriter.DIGEST).digest(ByteArray(0))
        log.info(
            "Anthropic adapter ready: {}://{}{}, version {}, connect timeout {}, request timeout {}",
            config.baseUrl.scheme,
            config.baseUrl.host,
            config.baseUrl.path,
            config.version,
            config.connectTimeout,
            config.requestTimeout,
        )
    }

    override suspend fun complete(call: ChatCall): ProviderResult =
        when (val written = writer.write(call)) {
            is WrittenRequest.Refused -> {
                logRefusal(call, written)
                ProviderResult.Failed(written.failure)
            }

            is WrittenRequest.Body -> {
                val started = clock.millis()
                val body = mapper.writeValueAsBytes(written.tree)
                val headers = headers(call)
                when (val exchange = upstream.exchange(endpoint, config.requestTimeout, headers, body)) {
                    is Exchange.Received -> interpret(call, exchange.response, started)
                    is Exchange.Broken -> transportFailure(call, exchange.failure, started)
                }
            }
        }

    override fun stream(call: ChatCall): Flow<ProviderStreamEvent> =
        flow {
            when (val written = writer.writeStream(call)) {
                is WrittenRequest.Refused -> {
                    logRefusal(call, written)
                    emit(ProviderStreamEvent.Failed(written.failure))
                }

                is WrittenRequest.Body -> {
                    val stream =
                        streams.stream(
                            call,
                            lifecycle = ::lifecycle,
                            headers = headers(call),
                            body = { mapper.writeValueAsBytes(written.tree) },
                        )
                    emitAll(stream)
                }
            }
        }

    private fun lifecycle(): StreamLifecycle =
        StreamLifecycle(
            AnthropicStreamDecoder(trees, classifier, clock, config.stream.maxEventBytes),
            config.stream.maxEventBytes,
        )

    private fun headers(call: ChatCall): (HttpHeaders) -> Unit =
        {
            it.set(API_KEY, config.apiKey.reveal())
            it.set(VERSION, config.version)
            it.set(CLIENT_REQUEST_ID, call.requestId.toString())
        }

    private fun interpret(
        call: ChatCall,
        response: ResponseEntity<ByteArray>,
        started: Long,
    ): ProviderResult {
        val body = response.body ?: EMPTY_BODY
        val success = response.statusCode.is2xxSuccessful
        val completed = if (success) reader.read(body) else null
        if (completed != null) {
            logSuccess(call, response, started, completed)
            return completed
        }
        logFailure(call, response, started)
        val status = response.statusCode.value()
        val failure =
            if (success) {
                ProviderFailure.UpstreamError
            } else {
                classifier.classify(status, response.headers, body, clock.instant())
            }
        return ProviderResult.Failed(failure)
    }

    private fun transportFailure(
        call: ChatCall,
        cause: Throwable,
        started: Long,
    ): ProviderResult {
        val failure = TransportFailures.classify(cause)
        log.warn(
            "Provider call failed: deployment={} exception={} durationMs={}",
            call.deployment.id.value,
            cause.sourceName(),
            clock.millis() - started,
        )
        return ProviderResult.Failed(failure)
    }

    private fun logRefusal(
        call: ChatCall,
        refused: WrittenRequest.Refused,
    ) {
        log.debug(
            "Provider call refused: deployment={} code={} param={}",
            call.deployment.id.value,
            refused.failure.code,
            refused.failure.param,
        )
    }

    private fun logSuccess(
        call: ChatCall,
        response: ResponseEntity<ByteArray>,
        started: Long,
        completed: ProviderResult.Completed,
    ) {
        log.debug(
            "Provider call answered: deployment={} status={} providerRequestId={} durationMs={} " +
                "inputTokens={} outputTokens={} estimated={}",
            call.deployment.id.value,
            response.statusCode.value(),
            providerRequestId(response.headers, REQUEST_ID),
            clock.millis() - started,
            completed.usage?.inputTokens?.value,
            completed.usage?.outputTokens?.value,
            completed.usage == null,
        )
    }

    private fun logFailure(
        call: ChatCall,
        response: ResponseEntity<ByteArray>,
        started: Long,
    ) {
        val fields = classifier.errorFields(response.body ?: EMPTY_BODY)
        log.warn(
            "Provider call failed: deployment={} status={} code={} type={} providerRequestId={} durationMs={}",
            call.deployment.id.value,
            response.statusCode.value(),
            fields.code,
            fields.type,
            providerRequestId(response.headers, REQUEST_ID),
            clock.millis() - started,
        )
    }

    override fun close() {
        upstream.close()
    }

    private companion object {
        const val POOL_NAME = "anthropic"
        const val API_KEY = "x-api-key"
        const val VERSION = "anthropic-version"

        // The provider's own ID: to the log only, never the gateway's x-request-id (GW-03 section 4).
        const val REQUEST_ID = "request-id"
        val EMPTY_BODY = ByteArray(0)
        private val log = LoggerFactory.getLogger(AnthropicChatAdapter::class.java)
    }
}
