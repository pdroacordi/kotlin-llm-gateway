package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.sse.SseEvent
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.Decoded
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamDecoder
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import java.time.Clock

private const val UNKNOWN_BLOCK = -1
private val KNOWN_EVENTS =
    setOf(
        "message_start",
        "content_block_start",
        "content_block_delta",
        "content_block_stop",
        "message_delta",
        "message_stop",
        "error",
    )

private fun nothing(): Decoded = Decoded.Open(emptyList())

private fun open(event: ProviderStreamEvent?): Decoded = Decoded.Open(listOfNotNull(event))

private fun failed(): Decoded = Decoded.Ended(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)))

private fun JsonNode.text(name: String): String? = get(name)?.takeIf { it.isString }?.stringValue()

private fun JsonNode.index(): Int = get("index")?.takeIf { it.canConvertToInt() }?.intValue() ?: UNKNOWN_BLOCK

/**
 * Anthropic's event sequence to OpenAI chunks (GW-03 section 4, Stream). Nothing is emitted before the first content or
 * tool delta, so an in-band error up to there is still a status (ADR-GW-03-007). One instance per stream, on one thread
 * at a time.
 */
internal class AnthropicStreamDecoder(
    private val trees: ObjectReader,
    private val classifier: AnthropicFailureClassifier,
    private val clock: Clock,
    maxHeldBytes: Int,
) : StreamDecoder {
    private var chunks: StreamChunks? = null
    private var counts = UsageCounts()
    private var finishSent = false
    private val thinking = HeldThinking(maxHeldBytes)

    @Volatile
    override var errorFields: ProviderErrorFields? = null
        private set

    override fun decode(event: SseEvent): Decoded {
        val name = event.event
        // Before any read: a ping or an unknown event is not worth parsing, and may not be JSON we know.
        if (name != null && name !in KNOWN_EVENTS) return nothing()
        val tree = trees.readTreeOrNull(event.data)?.takeIf { it.isObject }
        val type = name ?: tree?.text("type")
        val open = chunks
        return when {
            name == null && event.data.isEmpty() -> nothing()
            tree == null -> failed()
            type !in KNOWN_EVENTS -> nothing()
            type == "error" -> inBandError(tree)
            type == "message_start" -> messageStart(tree)
            open == null -> failed()
            else -> inMessage(type, tree, open)
        }
    }

    override fun atEnd(): List<ProviderStreamEvent> =
        if (finishSent) {
            listOfNotNull(usageChunk()) + ProviderStreamEvent.Completed
        } else {
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError))
        }

    private fun inMessage(
        type: String?,
        tree: JsonNode,
        chunks: StreamChunks,
    ): Decoded =
        when (type) {
            "content_block_start" -> blockStart(tree, chunks)
            "content_block_delta" -> blockDelta(tree, chunks)
            "content_block_stop" -> blockStop(tree, chunks)
            "message_delta" -> messageDelta(tree, chunks)
            else -> messageStop(chunks)
        }

    private fun messageStart(tree: JsonNode): Decoded {
        val node = tree.get("message")
        val id = node?.text("id")
        val model = node?.text("model")?.takeIf { it.isNotBlank() }
        if (id == null || model == null) return failed()
        chunks = StreamChunks(id, ModelName(model), clock.instant().epochSecond)
        counts = counts.startedBy(node.get("usage"))
        return nothing()
    }

    private fun blockStart(
        tree: JsonNode,
        chunks: StreamChunks,
    ): Decoded {
        val index = tree.index()
        val block = tree.get("content_block")
        val type = block?.text("type")
        return when {
            block == null || type == "text" -> {
                nothing()
            }

            type == "tool_use" -> {
                val id = block.text("id")
                val name = block.text("name")
                if (id == null || name == null) failed() else open(chunks.toolStart(index, id, name))
            }

            type == "thinking" -> {
                thinking.start(index, block.text("thinking").orEmpty(), block.text("signature").orEmpty())
                nothing()
            }

            type == "redacted_thinking" -> {
                thinking.hold(block.toJsonValue())
                nothing()
            }

            else -> {
                log.debug("Skipped a content block: type={}", type?.let(ProviderErrorFields::safeCode))
                nothing()
            }
        }
    }

    private fun blockDelta(
        tree: JsonNode,
        chunks: StreamChunks,
    ): Decoded {
        val index = tree.index()
        val delta = tree.get("delta")
        return when (delta?.text("type")) {
            "text_delta" -> {
                open(chunks.text(delta.text("text").orEmpty()))
            }

            "input_json_delta" -> {
                open(chunks.arguments(index, delta.text("partial_json").orEmpty()))
            }

            "thinking_delta" -> {
                thinking.appendText(index, delta.text("thinking").orEmpty())
                nothing()
            }

            "signature_delta" -> {
                thinking.appendSignature(index, delta.text("signature").orEmpty())
                nothing()
            }

            else -> {
                nothing()
            }
        }
    }

    private fun blockStop(
        tree: JsonNode,
        chunks: StreamChunks,
    ): Decoded {
        val index = tree.index()
        thinking.stop(index)
        return open(chunks.toolStop(index))
    }

    private fun messageDelta(
        tree: JsonNode,
        chunks: StreamChunks,
    ): Decoded {
        counts = counts.updatedBy(tree.get("usage"))
        val stopReason = tree.get("delta")?.text("stop_reason")
        if (stopReason == null || finishSent) return nothing()
        finishSent = true
        return open(chunks.finish(AnthropicStopReasons.finishReason(stopReason), thinking.blocks))
    }

    // No stop_reason reads as the non-stream reply does, so a finished answer keeps its usage (ADR-GW-03-021).
    private fun messageStop(chunks: StreamChunks): Decoded {
        val reason = AnthropicStopReasons.finishReason(AnthropicStopReasons.END_TURN)
        val finish = if (finishSent) null else chunks.finish(reason, thinking.blocks)
        finishSent = true
        return Decoded.Draining(listOfNotNull(finish, usageChunk()) + ProviderStreamEvent.Completed)
    }

    private fun inBandError(tree: JsonNode): Decoded {
        val fields = tree.get("error")?.let(classifier::errorFields) ?: ProviderErrorFields.NONE
        errorFields = fields
        return Decoded.Ended(listOf(ProviderStreamEvent.Failed(classifier.classifyInBand(fields))))
    }

    private fun usageChunk(): ProviderStreamEvent? =
        AnthropicUsageReader.usage(counts)?.let { usage -> chunks?.usage(usage) }

    private companion object {
        private val log = LoggerFactory.getLogger(AnthropicStreamDecoder::class.java)
    }
}
