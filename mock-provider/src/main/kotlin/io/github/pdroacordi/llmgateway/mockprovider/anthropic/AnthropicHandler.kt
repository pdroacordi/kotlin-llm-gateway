package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.provider.ProviderApi
import io.github.pdroacordi.llmgateway.mockprovider.web.Generation
import io.github.pdroacordi.llmgateway.mockprovider.web.Reply
import io.github.pdroacordi.llmgateway.mockprovider.web.noteModel
import io.github.pdroacordi.llmgateway.mockprovider.web.readJson
import io.github.pdroacordi.llmgateway.mockprovider.web.record
import io.github.pdroacordi.llmgateway.mockprovider.web.requireAccepts
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

/** `POST /v1/messages`: `x-api-key` or a bearer token; `anthropic-version` is recorded, not required. */
class AnthropicHandler(
    private val generation: Generation,
    private val renderer: AnthropicRenderer,
) {
    suspend fun messages(request: ServerRequest): ServerResponse {
        val record = request.record()
        // Credentials first, as the providers do: no key and a bad body is 401, not 400.
        generation.credentials.check(ProviderApi.ANTHROPIC, request.headers().asHttpHeaders())
        val body = request.readJson(generation.mapper)
        record.noteModel(body)
        val messages = AnthropicRequests.messages(body)
        record.model = messages.model
        record.stream = messages.stream
        if (!messages.stream) request.requireAccepts(MediaType.APPLICATION_JSON)

        val behaviour = generation.store.resolve(messages.model)
        val plan = generation.planner.plan(messages.conversation, messages.cap, behaviour.content, behaviour.usage)
        record.promptTokens = plan.usage.promptTokens
        record.completionTokens = plan.usage.completionTokens
        val seq = record.arrival.seq
        val reply =
            if (messages.stream) {
                renderer.stream(seq, messages.model, plan)
            } else {
                Reply.Json(
                    renderer.message(seq, messages.model, plan),
                    AnthropicRenderer.MALFORMED_BODY,
                    plan.pieces.size,
                )
            }
        return generation.serving.respond(record, behaviour, reply)
    }
}
