package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CLIENT_REQUEST_ID
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.Exchange
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.TransportFailures
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.UpstreamClient
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.providerRequestId
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.sourceName
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock

internal const val OPENAI_REQUEST_ID = "x-request-id"

/**
 * The OpenAI side of [ChatModelPort]: one POST whose status, headers and bytes go to pure functions
 * (docs/knowledge/patterns/upstream-bytes-then-pure-classification.md), for a stream chunk by chunk. No retries:
 * RTE-02 owns them.
 */
class OpenAiChatAdapter(
    private val config: OpenAiAdapterConfig,
    private val mapper: JsonMapper,
    private val clock: Clock,
) : ChatModelPort,
    AutoCloseable {
    private val trees = mapper.exactTreeReader()
    private val reader = OpenAiResponseReader(trees)
    private val classifier = OpenAiFailureClassifier(trees)
    private val endpoint = URI.create("${config.baseUrl.toString().trimEnd('/')}/chat/completions")
    private val upstream = UpstreamClient(POOL_NAME, config.connectTimeout, config.maxResponseBytes, config.pool)
    private val streams = OpenAiChatStream(upstream.web, endpoint, config, mapper, clock)

    /** Startup only; blocks. The first request would otherwise load these on an event loop. */
    fun warmUp() {
        upstream.warmUp(mapper, trees)
        log.info(
            "OpenAI adapter ready: {}://{}{}, connect timeout {}, request timeout {}",
            config.baseUrl.scheme,
            config.baseUrl.host,
            config.baseUrl.path,
            config.connectTimeout,
            config.requestTimeout,
        )
    }

    override suspend fun complete(call: ChatCall): ProviderResult {
        val started = clock.millis()
        val body = mapper.writeValueAsBytes(OpenAiRequestWriter.write(call, mapper.nodeFactory))
        val exchange =
            upstream.exchange(
                endpoint,
                config.requestTimeout,
                {
                    it.setBearerAuth(config.apiKey.reveal())
                    it.set(CLIENT_REQUEST_ID, call.requestId.toString())
                },
                body,
            )
        return when (exchange) {
            is Exchange.Received -> interpret(call, exchange.response, started)
            is Exchange.Broken -> transportFailure(call, exchange.failure, started)
        }
    }

    override fun stream(call: ChatCall): Flow<ProviderStreamEvent> = streams.stream(call)

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
            providerRequestId(response.headers, OPENAI_REQUEST_ID),
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
            providerRequestId(response.headers, OPENAI_REQUEST_ID),
            clock.millis() - started,
        )
    }

    override fun close() {
        upstream.close()
    }

    private companion object {
        const val POOL_NAME = "openai"
        val EMPTY_BODY = ByteArray(0)
        private val log = LoggerFactory.getLogger(OpenAiChatAdapter::class.java)
    }
}
