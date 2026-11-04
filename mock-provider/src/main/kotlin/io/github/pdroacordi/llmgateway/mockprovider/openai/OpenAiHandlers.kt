package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.generation.Embedder
import io.github.pdroacordi.llmgateway.mockprovider.journal.Outcome
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
import org.springframework.web.reactive.function.server.bodyValueAndAwait

/** `/v1/chat/completions`, `/v1/embeddings`, `/v1/models` (FND-03 section 4). */
class OpenAiHandlers(
    private val generation: Generation,
    private val renderer: OpenAiRenderer,
    private val embedder: Embedder,
    private val limits: EmbeddingLimits,
    private val models: ModelList,
) {
    suspend fun chat(request: ServerRequest): ServerResponse {
        val record = request.record()
        // Credentials first, as the providers do: no key and a bad body is 401, not 400.
        generation.credentials.check(ProviderApi.OPENAI, request.headers().asHttpHeaders())
        val body = request.readJson(generation.mapper)
        record.noteModel(body)
        val chat = OpenAiRequests.chat(body)
        record.model = chat.model
        record.stream = chat.stream
        record.includeUsage = chat.includeUsage
        if (!chat.stream) request.requireAccepts(MediaType.APPLICATION_JSON)

        val behaviour = generation.store.resolve(chat.model)
        val plan = generation.planner.plan(chat.conversation, chat.cap, behaviour.content, behaviour.usage)
        record.promptTokens = plan.usage.promptTokens
        record.completionTokens = plan.usage.completionTokens
        val seq = record.arrival.seq
        val created = generation.clock.instant().epochSecond
        val reply =
            if (chat.stream) {
                renderer.stream(seq, created, chat.model, plan, chat.includeUsage)
            } else {
                Reply.Json(
                    renderer.completion(seq, created, chat.model, plan),
                    OpenAiRenderer.MALFORMED_BODY,
                    plan.pieceCount,
                )
            }
        return generation.serving.respond(record, behaviour, reply)
    }

    suspend fun embeddings(request: ServerRequest): ServerResponse {
        val record = request.record()
        // Credentials first, as the providers do: no key and a bad body is 401, not 400.
        generation.credentials.check(ProviderApi.OPENAI, request.headers().asHttpHeaders())
        val body = request.readJson(generation.mapper)
        record.noteModel(body)
        val embeddings = OpenAiRequests.embeddings(body, limits)
        record.model = embeddings.model
        request.requireAccepts(MediaType.APPLICATION_JSON)

        val behaviour = generation.store.resolve(embeddings.model)
        val vectors = embeddings.inputs.map { embedder.embed(it, embeddings.dimensions) }
        val prompt = behaviour.usage.promptTokens ?: embeddings.inputs.sumOf { it.tokens }
        record.promptTokens = prompt
        val rendered =
            renderer.embeddings(
                embeddings.model,
                vectors,
                embeddings.base64,
                prompt.takeUnless {
                    behaviour.usage.omit
                },
            )
        return generation.serving.respond(record, behaviour, Reply.Json(rendered, OpenAiRenderer.MALFORMED_BODY, 0))
    }

    /** No scenario applies: the list has no model to key one on. */
    suspend fun models(request: ServerRequest): ServerResponse {
        val record = request.record()
        generation.credentials.check(ProviderApi.OPENAI, request.headers().asHttpHeaders())
        request.requireAccepts(MediaType.APPLICATION_JSON)
        record.finish(Outcome.Kind.COMPLETED, OK)
        return ServerResponse
            .ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValueAndAwait(renderer.models(models.ids, models.createdEpochSeconds))
    }

    private companion object {
        const val OK = 200
    }
}

/** `mock.models` and the startup time `/v1/models` reports as `created`. */
data class ModelList(
    val ids: List<String>,
    val createdEpochSeconds: Long,
)
