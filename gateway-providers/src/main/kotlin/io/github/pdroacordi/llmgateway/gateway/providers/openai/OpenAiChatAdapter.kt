package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatModelPort
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * The OpenAI side of [ChatModelPort]: one POST whose status, headers and bytes go to pure functions
 * (docs/knowledge/patterns/upstream-bytes-then-pure-classification.md). No retries: RTE-02 owns them.
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

    // Its own pool, so one provider's stall cannot exhaust another's (ADR-GW-01-004).
    private val connections =
        ConnectionProvider
            .builder(POOL_NAME)
            .maxConnections(config.pool.maxConnections)
            .pendingAcquireMaxCount(config.pool.maxConnections * PENDING_PER_CONNECTION)
            .pendingAcquireTimeout(config.pool.pendingAcquireTimeout)
            .maxIdleTime(config.pool.maxIdleTime)
            .maxLifeTime(config.pool.maxLifeTime)
            .evictInBackground(EVICTION_INTERVAL)
            .build()

    private val http =
        HttpClient
            .create(connections)
            .protocol(HttpProtocol.HTTP11)
            // Reactor Netty otherwise repeats a request once on some connection resets (ADR-GW-01-004: no retries).
            .disableRetry(true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeout.toMillis().toInt())

    private val client =
        WebClient
            .builder()
            .clientConnector(ReactorClientHttpConnector(http))
            .codecs { it.defaultCodecs().maxInMemorySize(config.maxResponseBytes) }
            .build()

    /** Startup only; blocks. The first request would otherwise load these on an event loop. */
    fun warmUp() {
        // docs/knowledge/gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use.md
        Class.forName(REACTIVE_FLOW)
        // Jackson builds its tree (de)serializers on first use under a ReentrantLock, which concurrent first requests
        // would contend for on event loops.
        trees.readTree(EMPTY_OBJECT)
        mapper.writeValueAsBytes(mapper.nodeFactory.objectNode())
        http.warmup().block(WARM_UP_TIMEOUT)
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
        return when (val exchange = exchange(call)) {
            is Exchange.Received -> interpret(call, exchange.response, started)
            is Exchange.Broken -> transportFailure(call, exchange.failure, started)
        }
    }

    // Named types only: a CancellationException passes through, and an unknown exception is a bug (GW-01 section 5).
    private suspend fun exchange(call: ChatCall): Exchange =
        try {
            Exchange.Received(post(call))
        } catch (failure: TimeoutException) {
            Exchange.Broken(failure)
        } catch (failure: WebClientException) {
            Exchange.Broken(failure)
        } catch (failure: IOException) {
            Exchange.Broken(failure)
        } catch (failure: DataBufferLimitException) {
            Exchange.Broken(failure)
        } catch (failure: DecodingException) {
            Exchange.Broken(failure)
        }

    // Mono.timeout, not withTimeout: its continuation would land on a thread BlockHound does not watch
    // (docs/knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md).
    private suspend fun post(call: ChatCall): ResponseEntity<ByteArray> {
        val body = mapper.writeValueAsBytes(OpenAiRequestWriter.write(call, mapper.nodeFactory))
        return client
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers {
                it.setBearerAuth(config.apiKey.reveal())
                it.set(CLIENT_REQUEST_ID, call.requestId.toString())
            }.bodyValue(body)
            .exchangeToMono { withoutContentType(it).toEntity(ByteArray::class.java) }
            .timeout(config.requestTimeout)
            .awaitSingle()
    }

    // The body is read as bytes whatever the type says, and reading it would parse a value a proxy may have mangled:
    // InvalidMediaTypeException is no transport failure, and would throw a paid answer away.
    private fun withoutContentType(response: ClientResponse): ClientResponse =
        response.mutate().headers { it.remove(HttpHeaders.CONTENT_TYPE) }.build()

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
        val failure = OpenAiTransportFailures.classify(cause)
        log.warn(
            "Provider call failed: deployment={} exception={} durationMs={}",
            call.deployment.id.value,
            cause.javaClass.name,
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
            providerRequestId(response.headers),
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
            providerRequestId(response.headers),
            clock.millis() - started,
        )
    }

    // A header from outside: only a value that looks like an ID reaches the log.
    private fun providerRequestId(headers: HttpHeaders): String? =
        headers.getFirst(REQUEST_ID)?.takeIf { PROVIDER_REQUEST_ID.matches(it) }

    override fun close() {
        connections.dispose()
    }

    private sealed interface Exchange {
        data class Received(
            val response: ResponseEntity<ByteArray>,
        ) : Exchange

        data class Broken(
            val failure: Throwable,
        ) : Exchange
    }

    private companion object {
        const val POOL_NAME = "openai"
        const val REACTIVE_FLOW = "kotlinx.coroutines.reactive.ReactiveFlowKt"

        // Reactor Netty's default for a custom pool.
        const val PENDING_PER_CONNECTION = 2
        const val REQUEST_ID = "x-request-id"
        const val CLIENT_REQUEST_ID = "X-Client-Request-Id"
        const val EMPTY_OBJECT = "{}"
        val EMPTY_BODY = ByteArray(0)
        val EVICTION_INTERVAL: Duration = Duration.ofSeconds(30)
        val WARM_UP_TIMEOUT: Duration = Duration.ofSeconds(30)
        val PROVIDER_REQUEST_ID = Regex("""[A-Za-z0-9_.-]{1,128}""")
        private val log = LoggerFactory.getLogger(OpenAiChatAdapter::class.java)
    }
}
