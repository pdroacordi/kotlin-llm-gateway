package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.auth.TeamContext
import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.app.web.parseJsonObject
import io.github.pdroacordi.llmgateway.gateway.app.web.readJsonBody
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingCpuPort
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingOutcome
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingRequest
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingService
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeoutException

/** `POST /v1/embeddings`. From the parse on it runs on the lane's threads, off the event loop (ADR-GW-05-012). */
class EmbeddingHandler(
    private val service: EmbeddingService,
    mapper: JsonMapper,
    private val cpu: EmbeddingCpuPort,
    private val admission: EmbeddingAdmission,
) {
    private val trees = mapper.exactTreeReader()
    private val writer = EmbeddingResponseWriter(mapper)

    suspend fun embed(request: ServerRequest): ServerResponse {
        val body = request.readJsonBody()
        admission.holdBody(request.exchange(), body)
        val embedding = typed(body)
        val context = checkNotNull(currentCoroutineContext()[RequestContext]) { "no RequestContext on this request" }
        // A route the filter missed is then a 500, never an anonymous call (ADR-GW-04-009).
        val team = checkNotNull(currentCoroutineContext()[TeamContext]) { "no TeamContext on this request" }.team
        admission.admit(request.exchange(), embedding)
        return when (val outcome = service.embed(context.requestId, team, embedding)) {
            is EmbeddingOutcome.Answered -> ok(outcome)
            is EmbeddingOutcome.Rejected -> throw GatewayErrorException(outcome.error)
            EmbeddingOutcome.Busy -> throw GatewayErrorException(admission.busy)
        }
    }

    private suspend fun typed(body: ByteArray?): EmbeddingRequest =
        try {
            checkNotNull(cpu.run { EmbeddingRequestReader.read(parseJsonObject(trees, body)) })
        } catch (_: TimeoutException) {
            // Nothing went upstream, so this is the gateway's own overload, not UpstreamTimeout.
            throw GatewayErrorException(admission.busy)
        } catch (_: RejectedExecutionException) {
            throw GatewayErrorException(admission.busy)
        }

    private suspend fun ok(outcome: EmbeddingOutcome.Answered): ServerResponse {
        val response =
            ServerResponse
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayHeaders.DEPLOYMENT, outcome.deployment.id.value)
        if (outcome.usage.estimated) response.header(GatewayHeaders.USAGE_ESTIMATED, "true")
        // Bytes, past every encoder: JacksonJsonEncoder logs the value it encodes (ADR-GW-05-008).
        return response.body(EmbeddingSlicedBody(writer, writer.write(outcome.list, outcome.usage))).awaitSingle()
    }
}
