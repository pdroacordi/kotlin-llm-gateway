package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.Usage
import io.github.pdroacordi.llmgateway.gateway.core.embedding.Embedding
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingList
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonNode
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.reactive.function.BodyInserter
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.Connection
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Writes [reply] with `Content-Length`, one slice at a time, so Netty's copy to direct memory is one slice and a client
 * that stops reading holds one (ADR-GW-05-013).
 */
internal class EmbeddingSlicedBody(
    private val writer: EmbeddingResponseWriter,
    private val reply: ByteBuffer,
) : BodyInserter<Unit, ServerHttpResponse> {
    override fun insert(
        outputMessage: ServerHttpResponse,
        context: BodyInserter.Context,
    ): Mono<Void> {
        val buffers = outputMessage.bufferFactory()
        outputMessage.headers.contentLength = reply.remaining().toLong()
        return outputMessage.writeAndFlushWith(paced(outputMessage).map { Mono.just(buffers.wrap(it)) })
    }

    private fun paced(response: ServerHttpResponse): Flux<ByteBuffer> {
        val slices = writer.slices(reply)
        if (reply.remaining() <= EmbeddingResponseWriter.SLICE_BYTES) return slices
        return ReplyWritePacer(ServerHttpResponseDecorator.getNativeResponse<Connection>(response)).pace(slices)
    }
}

/**
 * [EmbeddingList] to the OpenAI body. Vectors are appended as bytes: `writeRawValue` would widen each to a `char`, and
 * a tree would rewrite them (ADR-GW-05-005). Typed members come first, extras after them (GW-05 section 4).
 */
class EmbeddingResponseWriter(
    private val mapper: JsonMapper,
) {
    /** A view of the one buffer: `toByteArray` would add a third copy of a reply to ADR-GW-05-006's 2N of heap. */
    fun write(
        list: EmbeddingList,
        usage: Usage,
    ): ByteBuffer {
        val vectors =
            list.data.sumOf {
                it.vector.bytes.size
                    .toLong()
            }
        val size = vectors + list.data.size.toLong() * BYTES_PER_ITEM + FIXED_BYTES
        val out = Output(minOf(size, MAX_BUFFER).toInt())
        out.write(LIST_START)
        list.data.forEachIndexed { position, item ->
            if (position > 0) out.write(COMMA)
            out.item(item)
        }
        out.write(MODEL)
        out.write(mapper.writeValueAsBytes(list.model.value))
        out.write(USAGE)
        out.usage(usage)
        out.members(list.extras)
        if (usage.estimated) out.write(ESTIMATED)
        out.write(OBJECT_END)
        return out.view()
    }

    /** Views of [reply], so a slice costs no bytes until Netty copies it (ADR-GW-05-013). */
    fun slices(reply: ByteBuffer): Flux<ByteBuffer> {
        val start = reply.position()
        val end = reply.limit()
        val count = (reply.remaining() + SLICE_BYTES - 1) / SLICE_BYTES
        return Flux.range(0, count).map { index ->
            val from = start + index * SLICE_BYTES
            reply
                .duplicate()
                .limit(minOf(from + SLICE_BYTES, end))
                .position(from)
                .slice()
        }
    }

    private fun Output.item(item: Embedding) {
        write(ITEM_START)
        write(item.index.toString().encodeToByteArray())
        write(EMBEDDING)
        write(item.vector.bytes)
        members(item.extras)
        write(OBJECT_END)
    }

    private fun Output.usage(usage: Usage) {
        val tokens =
            usage.inputTokens.value
                .toString()
                .encodeToByteArray()
        write(PROMPT_TOKENS)
        write(tokens)
        write(TOTAL_TOKENS)
        write(tokens)
        members(usage.extras)
        write(OBJECT_END)
    }

    // The object's bytes less its braces, so each member follows the ones before it.
    private fun Output.members(extras: JsonValue.Object) {
        if (extras.members.isEmpty()) return
        val bytes = mapper.writeValueAsBytes(extras.toJsonNode(mapper.nodeFactory))
        write(COMMA)
        write(bytes, 1, bytes.size - 2)
    }

    private class Output(
        size: Int,
    ) : ByteArrayOutputStream(size) {
        fun view(): ByteBuffer = ByteBuffer.wrap(buf, 0, count)
    }

    internal companion object {
        const val SLICE_BYTES = 1024 * 1024

        // GW-05 section 5, "The writer".
        const val BYTES_PER_ITEM = 64L
        const val FIXED_BYTES = 1024L

        // The largest array a JVM reliably allocates.
        const val MAX_BUFFER = Int.MAX_VALUE - 8L

        val LIST_START = """{"object":"list","data":[""".encodeToByteArray()
        val ITEM_START = """{"object":"embedding","index":""".encodeToByteArray()
        val EMBEDDING = ""","embedding":""".encodeToByteArray()
        val MODEL = """],"model":""".encodeToByteArray()
        val USAGE = ""","usage":""".encodeToByteArray()
        val PROMPT_TOKENS = """{"prompt_tokens":""".encodeToByteArray()
        val TOTAL_TOKENS = ""","total_tokens":""".encodeToByteArray()
        val ESTIMATED =
            ""","${ChatExtensions.MEMBER}":{"${ChatExtensions.USAGE_ESTIMATED_MEMBER}":true}""".encodeToByteArray()
        val COMMA = ",".encodeToByteArray()
        val OBJECT_END = "}".encodeToByteArray()
    }
}
