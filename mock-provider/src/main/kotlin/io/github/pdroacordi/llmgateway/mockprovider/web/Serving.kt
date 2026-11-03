package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.FaultKind
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.InjectedError
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedBehaviour
import io.github.pdroacordi.llmgateway.mockprovider.error.MockConnectionAbort
import io.github.pdroacordi.llmgateway.mockprovider.journal.Outcome
import io.github.pdroacordi.llmgateway.mockprovider.journal.ScenarioRef
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.random.Random

/**
 * Applies a resolved behaviour to a rendered reply: latency, injected errors and faults (FND-03 section 5), and
 * records the outcome. Waits are [awaitDelay] only, never a blocking sleep (detekt `SleepInsteadOfDelay`, BlockHound).
 * A client disconnect cancels the coroutine or the stream; `client_cancelled` is recorded, the cancellation
 * rethrown.
 */
class Serving(
    private val bodies: ErrorBodies,
    private val maxHoldMs: Long,
    private val random: Random = Random.Default,
) {
    suspend fun respond(
        record: RequestRecord,
        behaviour: ResolvedBehaviour,
        reply: Reply,
    ): ServerResponse {
        record.scenario = behaviour.scenarioModel?.let { ScenarioRef(it, behaviour.step) }
        try {
            pause(behaviour.latency.ttftMs, behaviour.latency.jitterMs)
            return beforeContent(record, behaviour, reply is Reply.Stream)
                ?: when (reply) {
                    is Reply.Json -> json(record, behaviour, reply)
                    is Reply.Stream -> stream(record, behaviour, reply)
                }
        } finally {
            // Recorded, never caught: the cancellation propagates unchanged.
            if (!currentCoroutineContext().isActive) record.finish(Outcome.Kind.CLIENT_CANCELLED)
        }
    }

    /** Responses that replace the content: a held request, or an HTTP error. */
    private suspend fun beforeContent(
        record: RequestRecord,
        behaviour: ResolvedBehaviour,
        streaming: Boolean,
    ): ServerResponse? {
        val kind = behaviour.fault?.kind
        val error = behaviour.error
        return when {
            kind == FaultKind.HANG || (kind == FaultKind.STALL && !streaming) -> hold(record)

            // A non-stream request has no in-band channel: stream_error becomes an HTTP error.
            kind == FaultKind.STREAM_ERROR && !streaming -> injected(record, error ?: defaultStreamError(record.api))

            kind == null && error != null -> injected(record, error)

            else -> null
        }
    }

    private suspend fun json(
        record: RequestRecord,
        behaviour: ResolvedBehaviour,
        reply: Reply.Json,
    ): ServerResponse {
        pause(behaviour.latency.perTokenMs * reply.pieces, behaviour.latency.jitterMs)
        record.status(OK)
        return when (behaviour.fault?.kind) {
            FaultKind.DISCONNECT -> {
                // Status and headers committed, half the body sent, then the connection is aborted.
                val half = reply.body.substring(0, reply.body.length / 2)
                val frames =
                    recorded(record) {
                        emit(half)
                        record.finish(Outcome.Kind.ABORTED)
                        throw MockConnectionAbort("disconnect fault")
                    }
                ServerResponse
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(SseBodyInserter(frames))
                    .awaitSingle()
            }

            FaultKind.MALFORMED -> {
                record.finish(Outcome.Kind.MALFORMED)
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(reply.malformed)
            }

            else -> {
                record.finish(Outcome.Kind.COMPLETED)
                ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValueAndAwait(reply.body)
            }
        }
    }

    private suspend fun stream(
        record: RequestRecord,
        behaviour: ResolvedBehaviour,
        reply: Reply.Stream,
    ): ServerResponse {
        record.status(OK)
        val latency = behaviour.latency
        val frames =
            recorded(record) {
                reply.opening.forEach { emit(it) }
                // The opening is not a piece; a fault fires after `afterPieces` pieces, or after the last one.
                val faultAt = behaviour.fault?.let { minOf(it.afterPieces, reply.pieces.size) } ?: reply.pieces.size
                record.piecesSent = 0
                for (index in 0 until faultAt) {
                    emit(reply.pieces[index])
                    record.piecesSent = index + 1
                    pause(latency.perTokenMs, latency.jitterMs)
                }
                ending(record, behaviour, reply)
            }
        return ServerResponse
            .ok()
            .contentType(EVENT_STREAM)
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .body(SseBodyInserter(frames))
            .awaitSingle()
    }

    private suspend fun FlowCollector<String>.ending(
        record: RequestRecord,
        behaviour: ResolvedBehaviour,
        reply: Reply.Stream,
    ) {
        when (behaviour.fault?.kind) {
            null, FaultKind.HANG -> {
                reply.closing.forEach { emit(it) }
                record.finish(Outcome.Kind.COMPLETED)
            }

            FaultKind.STALL -> {
                awaitDelay(maxHoldMs)
                record.finish(Outcome.Kind.TIMED_OUT)
                throw MockConnectionAbort("stall reached mock.max-hold")
            }

            FaultKind.DISCONNECT -> {
                record.finish(Outcome.Kind.ABORTED)
                throw MockConnectionAbort("disconnect fault")
            }

            FaultKind.STREAM_ERROR -> {
                val error = behaviour.error ?: defaultStreamError(record.api)
                emit(reply.errorEvent(ErrorBodies.of(record.api, error)))
                record.finish(Outcome.Kind.STREAM_ERROR)
            }

            FaultKind.MALFORMED -> {
                emit(reply.malformed)
                reply.closing.forEach { emit(it) }
                record.finish(Outcome.Kind.MALFORMED)
            }
        }
    }

    /** `hang`: held until the client goes away or `mock.max-hold`, then 504 in the provider's shape. */
    private suspend fun hold(record: RequestRecord): ServerResponse {
        awaitDelay(maxHoldMs)
        val timeout = InjectedError(GATEWAY_TIMEOUT, message = "mock-provider held the request until mock.max-hold.")
        return error(record, ErrorBodies.of(record.api, timeout), Outcome.Kind.TIMED_OUT, emptyMap())
    }

    private suspend fun injected(
        record: RequestRecord,
        error: InjectedError,
    ): ServerResponse {
        val headers =
            buildMap {
                putAll(error.headers)
                error.retryAfterSeconds?.let { put(HttpHeaders.RETRY_AFTER, it.toString()) }
                error.retryAfterMs?.let { put(RETRY_AFTER_MS, it.toString()) }
            }
        return error(record, ErrorBodies.of(record.api, error), Outcome.Kind.ERROR_INJECTED, headers)
    }

    private suspend fun error(
        record: RequestRecord,
        body: ErrorBody,
        kind: Outcome.Kind,
        headers: Map<String, String>,
    ): ServerResponse {
        record.finish(kind, body.status)
        return ServerResponse
            .status(body.status)
            .contentType(MediaType.APPLICATION_JSON)
            .headers { target -> headers.forEach { (name, value) -> target.set(name, value) } }
            .bodyValueAndAwait(bodies.render(record.api, body, record.requestId))
    }

    /** A body that takes part in the record ([RequestRecord.join]) and records a client cancellation. */
    private fun recorded(
        record: RequestRecord,
        block: suspend FlowCollector<String>.() -> Unit,
    ): Flow<String> =
        flow {
            if (!record.join()) return@flow
            try {
                block()
            } finally {
                if (!currentCoroutineContext().isActive) record.finish(Outcome.Kind.CLIENT_CANCELLED)
                record.leave()
            }
        }

    /** [ms] plus a uniform 0..[jitterMs]. */
    private suspend fun pause(
        ms: Long,
        jitterMs: Long,
    ) {
        val total = ms + if (jitterMs > 0) random.nextLong(jitterMs + 1) else 0
        if (total > 0) awaitDelay(total)
    }

    private fun defaultStreamError(api: ProviderApi): InjectedError =
        InjectedError(if (api == ProviderApi.ANTHROPIC) OVERLOADED else INTERNAL_ERROR)

    companion object {
        val EVENT_STREAM = MediaType("text", "event-stream", Charsets.UTF_8)
        const val RETRY_AFTER_MS = "retry-after-ms"
        private const val OK = 200
        private const val INTERNAL_ERROR = 500
        private const val GATEWAY_TIMEOUT = 504

        // Anthropic's overloaded status; the default for its in-band stream error (FND-03 section 4).
        private const val OVERLOADED = 529
    }
}

/**
 * Waits [ms] on Reactor's `parallel` timer, so the caller resumes on a `parallel` thread (one per core, watched by
 * BlockHound). `delay` under Spring's `Dispatchers.Unconfined` would resume on a thread BlockHound does not watch
 * (docs/knowledge/gotchas/spring-coroutine-handlers-resume-on-default-executor-after-delay.md). Cancellation cancels
 * the timer.
 */
internal suspend fun awaitDelay(ms: Long) {
    Mono.delay(Duration.ofMillis(ms)).awaitSingle()
}
