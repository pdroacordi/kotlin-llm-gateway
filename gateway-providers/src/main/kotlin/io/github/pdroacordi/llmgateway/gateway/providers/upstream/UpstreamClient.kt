package io.github.pdroacordi.llmgateway.gateway.providers.upstream

import io.netty.channel.ChannelOption
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.core.codec.DecodingException
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientException
import reactor.core.publisher.Mono
import reactor.netty.http.HttpProtocol
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeoutException

internal const val CLIENT_REQUEST_ID = "X-Client-Request-Id"
private val PROVIDER_REQUEST_ID = Regex("""[A-Za-z0-9_.-]{1,128}""")

// A header from outside: only a value that looks like an ID reaches the log.
internal fun providerRequestId(
    headers: HttpHeaders,
    name: String,
): String? = headers.getFirst(name)?.takeIf { PROVIDER_REQUEST_ID.matches(it) }

// The body is read as bytes whatever the type says, and reading it would parse a value a proxy may have mangled:
// InvalidMediaTypeException is no transport failure, and would throw a paid answer away.
internal fun withoutContentType(response: ClientResponse): ClientResponse =
    response
        .mutate()
        .headers { it.remove(HttpHeaders.CONTENT_TYPE) }
        .body { it.bodyFailuresAsTransport() }
        .build()

// The chat path's read: the array is built on the event loop, which a reply of at most 16 MB can afford.
private fun readOnEventLoop(response: ClientResponse): Mono<ResponseEntity<ByteArray>> =
    withoutContentType(response).toEntity(ByteArray::class.java)

/** Joins the body on the event loop, without a copy, and leaves the copy to [lane] (ADR-GW-05-012). */
internal fun readOnLane(lane: CpuLane): (ClientResponse) -> Mono<ResponseEntity<ByteArray>> =
    { response ->
        withoutContentType(response).toEntity(DataBuffer::class.java).flatMap { entity ->
            val answer = ResponseEntity.status(entity.statusCode).headers(entity.headers)
            entity.body?.let { body -> lane.copy(body).map { answer.body(it) } } ?: Mono.just(answer.build())
        }
    }

/** What a non-stream POST came to: an answer of any status, or a failure for [TransportFailures]. */
internal sealed interface Exchange {
    data class Received(
        val response: ResponseEntity<ByteArray>,
    ) : Exchange

    data class Broken(
        val failure: Throwable,
    ) : Exchange
}

/** One provider's HTTP client, pool included. No retries: RTE-02 owns them. */
internal class UpstreamClient(
    private val name: String,
    connectTimeout: Duration,
    maxResponseBytes: Int,
    pool: PoolConfig,
) : AutoCloseable {
    // Its own pool, so one provider's stall cannot exhaust another's (ADR-GW-01-004).
    private val connections =
        ConnectionProvider
            .builder(name)
            .maxConnections(pool.maxConnections)
            .pendingAcquireMaxCount(pool.maxConnections * PENDING_PER_CONNECTION)
            .pendingAcquireTimeout(pool.pendingAcquireTimeout)
            .maxIdleTime(pool.maxIdleTime)
            .maxLifeTime(pool.maxLifeTime)
            .evictInBackground(EVICTION_INTERVAL)
            .build()

    private val http =
        HttpClient
            .create(connections)
            .protocol(HttpProtocol.HTTP11)
            // Reactor Netty otherwise repeats a request once on some connection resets (ADR-GW-01-004: no retries).
            .disableRetry(true)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())

    val web: WebClient =
        WebClient
            .builder()
            .clientConnector(ReactorClientHttpConnector(http))
            .codecs { it.defaultCodecs().maxInMemorySize(maxResponseBytes) }
            .build()

    /** Startup only; blocks. The first request would otherwise load these on an event loop. */
    fun warmUp(
        mapper: JsonMapper,
        trees: ObjectReader,
    ) {
        // docs/knowledge/gotchas/kotlinx-reactive-await-loads-context-injectors-on-first-use.md
        Class.forName(REACTIVE_FLOW)
        // Jackson builds its tree (de)serializers on first use under a ReentrantLock, which concurrent first requests
        // would contend for on event loops.
        trees.readTree(EMPTY_OBJECT)
        mapper.writeValueAsBytes(mapper.nodeFactory.objectNode())
        http.warmup().block(WARM_UP_TIMEOUT)
    }

    /** Named types only: a CancellationException passes through, and an unknown one is a bug (GW-01 section 5). */
    suspend fun exchange(
        endpoint: URI,
        timeout: Duration,
        headers: (HttpHeaders) -> Unit,
        body: ByteArray,
        read: (ClientResponse) -> Mono<ResponseEntity<ByteArray>> = ::readOnEventLoop,
    ): Exchange =
        try {
            Exchange.Received(post(endpoint, timeout, headers, body, read))
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
    private suspend fun post(
        endpoint: URI,
        timeout: Duration,
        headers: (HttpHeaders) -> Unit,
        body: ByteArray,
        read: (ClientResponse) -> Mono<ResponseEntity<ByteArray>>,
    ): ResponseEntity<ByteArray> =
        web
            .post()
            .uri(endpoint)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers)
            .bodyValue(body)
            .exchangeToMono(read)
            .timeout(timeout)
            .awaitSingle()

    override fun close() {
        connections.dispose()
        log.info("Provider pool closed: name={}", name)
    }

    private companion object {
        const val REACTIVE_FLOW = "kotlinx.coroutines.reactive.ReactiveFlowKt"

        // Reactor Netty's default for a custom pool.
        const val PENDING_PER_CONNECTION = 2
        const val EMPTY_OBJECT = "{}"
        val EVICTION_INTERVAL: Duration = Duration.ofSeconds(30)
        val WARM_UP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(UpstreamClient::class.java)
    }
}
