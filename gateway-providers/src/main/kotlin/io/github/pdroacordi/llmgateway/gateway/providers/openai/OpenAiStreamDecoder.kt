package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEvent
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.Decoded
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamDecoder

/** OpenAI's part of a stream: `[DONE]`, chunks, and an in-band `error` object (GW-02 section 5). */
internal class OpenAiStreamDecoder(
    private val reader: OpenAiChunkReader,
    private val classifier: OpenAiFailureClassifier,
) : StreamDecoder {
    private var finishSeen = false

    @Volatile
    override var errorFields: ProviderErrorFields? = null
        private set

    override fun decode(event: SseEvent): Decoded =
        when {
            event.data.isEmpty() -> Decoded.Open(emptyList())
            startsWithDone(event.data) -> Decoded.Draining(listOf(ProviderStreamEvent.Completed))
            else -> read(event.data)
        }

    override fun atEnd(): List<ProviderStreamEvent> =
        if (finishSeen) {
            listOf(ProviderStreamEvent.Completed)
        } else {
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError))
        }

    private fun read(data: ByteArray): Decoded =
        when (val read = reader.read(data)) {
            is ChunkRead.Read -> {
                finishSeen = finishSeen || read.chunk.choices.any { it.finishReason != null }
                Decoded.Open(listOf(ProviderStreamEvent.Chunk(read.chunk)))
            }

            is ChunkRead.InBandError -> {
                errorFields = read.fields
                Decoded.Ended(listOf(ProviderStreamEvent.Failed(classifier.classifyInBand(read.fields))))
            }

            ChunkRead.Unreadable -> {
                Decoded.Ended(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)))
            }
        }

    // A prefix, as both SDKs read it.
    private fun startsWithDone(data: ByteArray): Boolean =
        data.size >= DONE.size && DONE.indices.all { data[it] == DONE[it] }

    private companion object {
        val DONE = "[DONE]".encodeToByteArray()
    }
}
