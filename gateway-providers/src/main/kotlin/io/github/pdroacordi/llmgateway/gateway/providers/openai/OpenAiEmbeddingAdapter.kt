package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCall
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingModelPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingResult
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CLIENT_REQUEST_ID
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CpuLane
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.Exchange
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.TransportFailures
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.UpstreamClient
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.readOnLane
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/**
 * The OpenAI side of [EmbeddingModelPort]: one POST on a pool of its own, so a batch cannot take connections from a
 * chat stream (ADR-GW-05-002). No retries: RTE-02 owns them.
 */
class OpenAiEmbeddingAdapter internal constructor(
    private val config: OpenAiEmbeddingAdapterConfig,
    private val mapper: JsonMapper,
    private val clock: Clock,
    private val readReply: (ByteArray) -> EmbeddingResult.Embedded?,
) : EmbeddingModelPort,
    AutoCloseable {
    constructor(
        config: OpenAiEmbeddingAdapterConfig,
        mapper: JsonMapper,
        clock: Clock,
    ) : this(config, mapper, clock, OpenAiEmbeddingReader(mapper.exactTreeReader())::read)

    private val trees = mapper.exactTreeReader()
    private val classifier = OpenAiFailureClassifier(trees)
    private val calls = OpenAiCallLog(OpenAiEmbeddingAdapter::class.java, clock)
    private val endpoint = URI.create("${config.baseUrl.toString().trimEnd('/')}/embeddings")

    // Must not begin with `openai`: ProviderLifecycleTest counts lines holding `name=openai` (ADR-GW-05-002).
    private val upstream = UpstreamClient(POOL_NAME, config.connectTimeout, config.maxResponseBytes, config.pool)

    /** Owned here and closed with the adapter; the handler borrows it for the request's parse (ADR-GW-05-012). */
    val lane = CpuLane(LANE_NAME, config.cpuThreads, config.cpuQueueSize, config.requestTimeout)

    /** Startup only; blocks. The first request would otherwise load these on an event loop. */
    fun warmUp() {
        upstream.warmUp(mapper, trees)
        checkNotNull(readReply(SAMPLE_REPLY)) { "The sample reply must be readable." }
        log.info(
            "OpenAI embedding adapter ready: {}://{}{}, connect timeout {}, request timeout {}",
            config.baseUrl.scheme,
            config.baseUrl.host,
            config.baseUrl.path,
            config.connectTimeout,
            config.requestTimeout,
        )
    }

    override suspend fun embed(call: EmbeddingCall): EmbeddingResult {
        val started = clock.millis()
        return try {
            when (val exchange = exchange(call)) {
                is Exchange.Received -> interpret(call, exchange.response, started)
                is Exchange.Broken -> broken(call, exchange.failure, started)
            }
        } catch (deadline: TimeoutException) {
            // Only the lane's: `exchange` answers its own as Broken.
            transportFailure(call, deadline, started)
        } catch (_: RejectedExecutionException) {
            // Shutdown only: a stage past admission is refused by nothing but a closed lane. The provider may bill, yet
            // none failed, so RTE-02 must not fall back on this.
            EmbeddingResult.Busy
        }
    }

    private suspend fun exchange(call: EmbeddingCall): Exchange {
        val body =
            checkNotNull(
                lane.runAdmitted {
                    mapper.writeValueAsBytes(OpenAiEmbeddingRequestWriter.write(call, mapper.nodeFactory))
                },
            )
        return upstream.exchange(
            endpoint,
            config.requestTimeout,
            {
                it.setBearerAuth(config.apiKey.reveal())
                it.set(CLIENT_REQUEST_ID, call.requestId.toString())
            },
            body,
            readOnLane(lane),
        )
    }

    private suspend fun interpret(
        call: EmbeddingCall,
        response: ResponseEntity<ByteArray>,
        started: Long,
    ): EmbeddingResult {
        val body = response.body ?: EMPTY_BODY
        if (response.statusCode.is2xxSuccessful) return success(call, response, body, started)
        val fields = errorFields(body)
        calls.failed(call.deployment, response, fields, started)
        val status = response.statusCode.value()
        return EmbeddingResult.Failed(classifier.classify(status, response.headers, fields, clock.instant()))
    }

    // The limit that admits a 256 MB reply admits an error body of that size too (ADR-GW-05-014).
    private suspend fun errorFields(body: ByteArray): ProviderErrorFields =
        if (body.size > ERROR_BODY_GUARD_BYTES) {
            ProviderErrorFields.NONE
        } else {
            checkNotNull(lane.runAdmitted { classifier.errorFields(body) })
        }

    private suspend fun success(
        call: EmbeddingCall,
        response: ResponseEntity<ByteArray>,
        body: ByteArray,
        started: Long,
    ): EmbeddingResult {
        // Off the event loop, which chat streams share (ADR-GW-05-010).
        val embedded = lane.runAdmitted { readReply(body) }
        if (embedded == null) {
            // NONE: reading a 2xx for error fields would build the tree this adapter exists to avoid.
            calls.failed(call.deployment, response, ProviderErrorFields.NONE, started)
            return EmbeddingResult.Failed(ProviderFailure.UpstreamError)
        }
        calls.answered(call.deployment, response, started, embedded.usage)
        return embedded
    }

    // A full pool is the gateway's own queue: no provider failed, and RTE-02 must not fall back on it (ADR-GW-05-011).
    private fun broken(
        call: EmbeddingCall,
        failure: Throwable,
        started: Long,
    ): EmbeddingResult =
        if (TransportFailures.poolQueueFull(failure)) {
            EmbeddingResult.Busy
        } else {
            transportFailure(call, failure, started)
        }

    private fun transportFailure(
        call: EmbeddingCall,
        cause: Throwable,
        started: Long,
    ): EmbeddingResult {
        val failure = TransportFailures.classify(cause)
        calls.broken(call.deployment, cause, started)
        return EmbeddingResult.Failed(failure)
    }

    override fun close() {
        lane.close()
        upstream.close()
    }

    private companion object {
        const val POOL_NAME = "embeddings-openai"
        const val LANE_NAME = "embeddings-openai-cpu"
        const val ERROR_BODY_GUARD_BYTES = 64 * 1024
        val EMPTY_BODY = ByteArray(0)
        val SAMPLE_REPLY =
            """
            {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.5],"x":1},
            {"object":"embedding","index":1,"embedding":"AAAAPw=="}],"model":"m","x":null,
            "usage":{"prompt_tokens":1,"total_tokens":1}}
            """.trimIndent().encodeToByteArray()
        private val log = LoggerFactory.getLogger(OpenAiEmbeddingAdapter::class.java)
    }
}
