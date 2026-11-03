package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.error.MockConnectionAbort
import io.github.pdroacordi.llmgateway.mockprovider.error.toMockReply
import io.github.pdroacordi.llmgateway.mockprovider.journal.CredentialFingerprint
import io.github.pdroacordi.llmgateway.mockprovider.journal.HeaderRedaction
import io.github.pdroacordi.llmgateway.mockprovider.journal.Outcome
import io.github.pdroacordi.llmgateway.mockprovider.journal.PresentedCredential
import io.github.pdroacordi.llmgateway.mockprovider.journal.RequestJournal
import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * The first filter. Every request outside `/__mock` and `/actuator` gets an arrival sequence and one journal entry,
 * misrouted ones included: counting here, not in a handler, is what makes `lastSeq` the "did anything reach the
 * provider" signal (ADR-FND-03-008). It sets the provider request-ID header before anything can fail.
 */
class ArrivalWebFilter(
    private val journal: RequestJournal,
    private val fingerprint: CredentialFingerprint,
    private val capture: BodyCapture,
) : CoWebFilter(),
    Ordered {
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    // Every failure is journaled before the mapper sees it, whatever its type.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        val request = exchange.request
        val path = request.path.value()
        if (!isCounted(path)) return chain.filter(exchange)

        val record = arrive(exchange)
        exchange.attributes[RequestRecord.ATTRIBUTE] = record
        val idHeader = if (record.api == ProviderApi.ANTHROPIC) ANTHROPIC_REQUEST_ID else OPENAI_REQUEST_ID
        exchange.response.headers.set(idHeader, record.requestId)
        try {
            chain.filter(exchange)
            record.finishIfAbsent(Outcome.Kind.COMPLETED, exchange.response.statusCode?.value())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            when (failure) {
                is MockConnectionAbort -> {
                    record.finishIfAbsent(
                        Outcome.Kind.ABORTED,
                        exchange.response.statusCode?.value(),
                    )
                }

                else -> {
                    record.finishIfAbsent(
                        Outcome.Kind.REJECTED,
                        failure.toMockReply(request.method.name(), path).status,
                    )
                }
            }
            throw failure
        } finally {
            // A client that went away cancels this coroutine.
            if (!currentCoroutineContext().isActive) record.finishIfAbsent(Outcome.Kind.CLIENT_CANCELLED, null)
            record.leave()
        }
    }

    private fun arrive(exchange: ServerWebExchange): RequestRecord {
        val request = exchange.request
        val headers = request.headers
        val path = request.path.value()
        val presented = PresentedCredential.of(headers.getFirst(HttpHeaders.AUTHORIZATION), headers.getFirst(X_API_KEY))
        val received =
            Received(
                route = routeOf(path),
                method = request.method.name(),
                path = path,
                headers = HeaderRedaction.redact(headers.headerNames().associateWith { headers.getValuesAsList(it) }),
                credential = presented?.let(fingerprint::of),
            )
        return RequestRecord(journal.arrive(), received, capture) { done ->
            val entry = done.toEntry()
            journal.complete(done.arrival, entry)
            log.debug(
                "seq={} route={} model={} status={} outcome={} durationMs={}",
                entry.seq,
                entry.route,
                entry.model?.let { logSafe(it) },
                entry.outcome.status,
                entry.outcome.kind.wire,
                entry.durationMs,
            )
        }
    }

    companion object {
        const val OPENAI_REQUEST_ID = "x-request-id"
        const val ANTHROPIC_REQUEST_ID = "request-id"
        const val X_API_KEY = "x-api-key"
        private val log = LoggerFactory.getLogger(ArrivalWebFilter::class.java)

        private val ROUTES =
            mapOf(
                "/v1/chat/completions" to "openai.chat",
                "/v1/embeddings" to "openai.embeddings",
                "/v1/models" to "openai.models",
                "/v1/messages" to "anthropic.messages",
            )

        /** [text] with control characters written as `\uXXXX`, so a model name cannot forge log lines. */
        fun logSafe(text: String): String =
            buildString {
                text.forEach { char ->
                    if (char.isISOControl()) append("\\u%04x".format(Locale.ROOT, char.code)) else append(char)
                }
            }

        fun isCounted(path: String): Boolean = !isUnder(path, "/__mock") && !isUnder(path, "/actuator")

        /** The route id by path alone: a wrong method on a provider path still counts under that route. */
        fun routeOf(path: String): String = ROUTES[path] ?: "unknown"

        private fun isUnder(
            path: String,
            prefix: String,
        ): Boolean = path == prefix || path.startsWith("$prefix/")
    }
}
