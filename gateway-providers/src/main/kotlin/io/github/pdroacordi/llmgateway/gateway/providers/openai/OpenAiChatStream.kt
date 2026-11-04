package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCall
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.CLIENT_REQUEST_ID
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.SseStreamCall
import kotlinx.coroutines.flow.Flow
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.Clock

/** The stream half of the adapter: OpenAI's body, headers and decoding around [SseStreamCall]. */
internal class OpenAiChatStream(
    client: WebClient,
    endpoint: URI,
    private val config: OpenAiAdapterConfig,
    private val mapper: JsonMapper,
    clock: Clock,
) {
    private val trees = mapper.exactTreeReader()
    private val reader = OpenAiChunkReader(trees)
    private val classifier = OpenAiFailureClassifier(trees)
    private val calls = SseStreamCall(client, endpoint, config.stream, classifier, OPENAI_REQUEST_ID, clock)

    fun stream(call: ChatCall): Flow<ProviderStreamEvent> =
        calls.stream(
            call,
            lifecycle = { OpenAiStreamTranslator(reader, classifier, config.stream.maxEventBytes) },
            headers = {
                it.setBearerAuth(config.apiKey.reveal())
                it.set(CLIENT_REQUEST_ID, call.requestId.toString())
            },
            body = { mapper.writeValueAsBytes(OpenAiRequestWriter.writeStream(call, mapper.nodeFactory)) },
        )
}
