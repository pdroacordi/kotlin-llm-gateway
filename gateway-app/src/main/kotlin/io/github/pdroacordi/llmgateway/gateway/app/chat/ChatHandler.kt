package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.app.request.GatewayHeaders
import io.github.pdroacordi.llmgateway.gateway.app.request.RequestContext
import io.github.pdroacordi.llmgateway.gateway.app.web.readJsonObject
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletionService
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatOutcome
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import kotlinx.coroutines.currentCoroutineContext
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import tools.jackson.databind.json.JsonMapper

/** `POST /v1/chat/completions`, non-streaming until GW-02. */
class ChatHandler(
    private val service: ChatCompletionService,
    private val mapper: JsonMapper,
) {
    private val trees = mapper.exactTreeReader()

    suspend fun complete(request: ServerRequest): ServerResponse {
        val chat = ChatRequestReader.read(request.readJsonObject(trees))
        val context = checkNotNull(currentCoroutineContext()[RequestContext]) { "no RequestContext on a chat request" }
        return when (val outcome = service.complete(context.requestId, chat)) {
            is ChatOutcome.Answered -> ok(outcome)
            is ChatOutcome.Rejected -> throw GatewayErrorException(outcome.error)
        }
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
