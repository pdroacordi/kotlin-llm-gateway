package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingReplyBudget
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingReplyLimits
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingRequest
import io.github.pdroacordi.llmgateway.gateway.core.embedding.worstCaseReplyBytes
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

/** Reserves a call's body and worst-case reply from the process-wide budget, or refuses it (ADR-GW-05-011). */
class EmbeddingAdmission(
    private val budget: EmbeddingReplyBudget,
    private val limits: EmbeddingReplyLimits,
    retryAfter: Duration,
) {
    val busy = GatewayError.Overloaded(retryAfter)

    /** Before the parse, so a call queued for the lane is counted. A bad body under a full budget is refused too. */
    fun holdBody(
        exchange: ServerWebExchange,
        body: ByteArray?,
    ) {
        val bytes = (body?.size ?: 0) * BODY_COPIES
        hold(exchange).keep(budget.tryReserve(bytes) ?: throw GatewayErrorException(busy))
    }

    fun admit(
        exchange: ServerWebExchange,
        request: EmbeddingRequest,
    ) {
        val hold = hold(exchange)
        val reply = budget.tryReserveBeside(worstCaseReplyBytes(request, limits), hold.heldBytes)
        hold.keep(reply ?: throw GatewayErrorException(busy))
    }

    private fun hold(exchange: ServerWebExchange): ReplyHold =
        checkNotNull(exchange.getAttribute<ReplyHold>(ReplyHold.ATTRIBUTE)) { "no ReplyHold on this request" }

    private companion object {
        // The body, then what a call keeps through the upstream call: the typed input and the outbound array.
        const val BODY_COPIES = 2L
    }
}

/**
 * Where a call's reservations live from the handler to the end of the response write. Whichever of [keep] and [end]
 * runs second sees the other, so a cancel that races the handler cannot leak one, and a release runs only once.
 */
internal class ReplyHold {
    private val reservations = ConcurrentLinkedQueue<EmbeddingReplyBudget.Reservation>()

    @Volatile
    private var ended = false

    val heldBytes: Long get() = reservations.sumOf { it.bytes }

    fun keep(reservation: EmbeddingReplyBudget.Reservation) {
        reservations += reservation
        if (ended) reservation.release()
    }

    fun end() {
        ended = true
        reservations.forEach { it.release() }
    }

    companion object {
        const val ATTRIBUTE = "llmgw.embedding.replyHold"
    }
}

/**
 * Ends a call's [ReplyHold] with the exchange. A handler returns before the write; only this chain sees completion,
 * an error, a cancel and a client disconnect alike.
 */
class EmbeddingReplyFilter : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        // The router's own matcher: a string compare would miss `/v1/embeddings;a=b`, which reaches the handler.
        if (!ROUTE.matches(exchange.request.path.pathWithinApplication())) return chain.filter(exchange)
        val hold = ReplyHold()
        exchange.attributes[ReplyHold.ATTRIBUTE] = hold
        return chain.filter(exchange).doFinally { hold.end() }
    }

    private companion object {
        val ROUTE = PathPatternParser.defaultInstance.parse(EmbeddingRoutes.EMBEDDINGS)
    }
}
