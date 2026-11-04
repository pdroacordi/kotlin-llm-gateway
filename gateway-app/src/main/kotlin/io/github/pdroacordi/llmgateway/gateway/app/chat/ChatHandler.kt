package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.auth.TeamContext
import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.error.StreamErrorFrames
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.app.web.SseBodyInserter
import io.github.pdroacordi.llmgateway.gateway.app.web.readJsonObject
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletionService
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatOutcome
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatRequest
import io.github.pdroacordi.llmgateway.gateway.core.team.TeamId
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** `POST /v1/chat/completions`; `stream` selects the port method. */
class ChatHandler(
    private val service: ChatCompletionService,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    private val trees = mapper.exactTreeReader()
    private val streamErrors = StreamErrorFrames(mapper)

    suspend fun complete(request: ServerRequest): ServerResponse {
        val body = request.readJsonObject(trees)
        val chat = ChatRequestReader.read(body)
        val context = checkNotNull(currentCoroutineContext()[RequestContext]) { "no RequestContext on a chat request" }
        // A route the filter missed is then a 500, never an anonymous call (ADR-GW-04-009).
        val team = checkNotNull(currentCoroutineContext()[TeamContext]) { "no TeamContext on a chat request" }.team
        return if (ChatRequestReader.wantsStream(body)) stream(context, team, chat) else answer(context, team, chat)
    }

    private suspend fun answer(
        context: RequestContext,
        team: TeamId,
        chat: ChatRequest,
    ): ServerResponse =
        when (val outcome = service.complete(context.requestId, team, chat)) {
            is ChatOutcome.Answered -> ok(outcome)
            is ChatOutcome.Rejected -> throw GatewayErrorException(outcome.error)
        }

    // Cold: the provider call starts when the response body is subscribed, after this returns.
    private suspend fun stream(
        context: RequestContext,
        team: TeamId,
        chat: ChatRequest,
    ): ServerResponse {
        val log = ChatStreamLog(clock, context.requestId)
        val events = service.stream(context.requestId, team, chat, log::end)
        val includeUsage = chat.streamOptions?.includeUsage == true
        // Without the Job, so asFlux's coroutine does not join a handler that returns before the body is written.
        val coroutine = currentCoroutineContext().minusKey(Job)
        val inserter =
            SseBodyInserter(coroutine) { headers ->
                ChatStreamFrames(mapper, streamErrors, context.requestId, includeUsage, headers, log).frames(events)
            }
        return ServerResponse.ok().body(inserter).awaitSingle()
    }

    private suspend fun ok(outcome: ChatOutcome.Answered): ServerResponse {
        val response =
            ServerResponse
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(GatewayHeaders.DEPLOYMENT, outcome.deployment.id.value)
        if (outcome.usage.estimated) response.header(GatewayHeaders.USAGE_ESTIMATED, "true")
        val tree = ChatResponseWriter.write(outcome.completion, outcome.usage, mapper.nodeFactory)
        // Bytes: JacksonJsonEncoder logs the value it encodes, which is completion text; ByteArrayEncoder logs a size.
        return response.bodyValueAndAwait(mapper.writeValueAsBytes(tree))
    }
}
