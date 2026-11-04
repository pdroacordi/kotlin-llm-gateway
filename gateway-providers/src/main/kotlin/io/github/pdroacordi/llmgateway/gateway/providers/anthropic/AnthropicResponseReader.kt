package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.AssistantMessage
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChoice
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatCompletion
import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderResult
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.readTreeOrNull
import io.github.pdroacordi.llmgateway.gateway.providers.json.toJsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.ProviderErrorFields
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** `stop_reason` to `finish_reason` (ADR-GW-03-010): a strict SDK can fail on a value outside OpenAI's enum. */
internal object AnthropicStopReasons {
    const val END_TURN = "end_turn"
    private val log = LoggerFactory.getLogger(AnthropicStopReasons::class.java)
    private val STOP = FinishReason("stop")
    private val LENGTH = FinishReason("length")
    private val REASONS =
        mapOf(
            END_TURN to STOP,
            "stop_sequence" to STOP,
            "max_tokens" to LENGTH,
            "model_context_window_exceeded" to LENGTH,
            "pause_turn" to LENGTH,
            "tool_use" to FinishReason("tool_calls"),
            "refusal" to FinishReason("content_filter"),
        )

    fun finishReason(stopReason: String): FinishReason =
        REASONS[stopReason] ?: STOP.also {
            log.warn("Unknown stop reason: value={}", ProviderErrorFields.safeCode(stopReason))
        }
}

/** A 2xx's body to a completion, or null when it is not one; the caller answers `UpstreamError`. */
internal class AnthropicResponseReader(
    private val trees: ObjectReader,
    private val mapper: JsonMapper,
    private val clock: Clock,
) {
    fun read(body: ByteArray): ProviderResult.Completed? =
        trees.readTreeOrNull(body)?.let { tree ->
            completion(tree)?.let { ProviderResult.Completed(it, AnthropicUsageReader.read(tree.get("usage"))) }
        }

    private fun completion(tree: JsonNode): ChatCompletion? {
        val id = tree.get("id")?.takeIf { it.isString }?.stringValue()
        val model =
            tree
                .get("model")
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
        val content = tree.get("content")?.takeIf { it.isArray }
        val blocks = content?.let(::blocks)
        return when {
            id == null || model == null || blocks == null -> {
                null
            }

            else -> {
                val choice = ChatChoice(0, blocks.message(), finishReason(tree), LOGPROBS)
                ChatCompletion(id, clock.instant().epochSecond, ModelName(model), listOf(choice))
            }
        }
    }

    private fun finishReason(tree: JsonNode): FinishReason {
        val stopReason = tree.get("stop_reason")?.takeIf { it.isString }?.stringValue()
        return AnthropicStopReasons.finishReason(stopReason ?: AnthropicStopReasons.END_TURN)
    }

    private fun blocks(content: JsonNode): Blocks? {
        val blocks = Blocks()
        content.values().forEach { block ->
            val type = block.get("type")?.takeIf { it.isString }?.stringValue()
            when (type) {
                "text" -> {
                    blocks.text.append(
                        block
                            .get("text")
                            ?.takeIf { it.isString }
                            ?.stringValue()
                            .orEmpty(),
                    )
                }

                "tool_use" -> {
                    blocks.toolCalls += toolCall(block) ?: return null
                }

                "thinking", "redacted_thinking" -> {
                    blocks.thinking += block.toJsonValue()
                }

                else -> {
                    log.debug("Skipped a content block: type={}", type?.let(ProviderErrorFields::safeCode))
                }
            }
        }
        return blocks
    }

    private fun toolCall(block: JsonNode): JsonValue? {
        val id = block.get("id")?.takeIf { it.isString }?.stringValue()
        val name = block.get("name")?.takeIf { it.isString }?.stringValue()
        if (id == null || name == null) return null
        val input = block.get("input")?.takeUnless { it.isNull } ?: mapper.nodeFactory.objectNode()
        return ReplyMembers.whole(id, name, mapper.writeValueAsString(input))
    }

    private class Blocks {
        val text = StringBuilder()
        val toolCalls = mutableListOf<JsonValue>()
        val thinking = mutableListOf<JsonValue>()

        fun message(): AssistantMessage {
            val members =
                buildMap {
                    if (toolCalls.isNotEmpty()) put("tool_calls", JsonValue.Array(toolCalls))
                    put("refusal", JsonValue.Null)
                    if (thinking.isNotEmpty()) put(ChatExtensions.MEMBER, ReplyMembers.thinkingExtension(thinking))
                }
            val content = if (text.isEmpty() && toolCalls.isNotEmpty()) null else text.toString()
            return AssistantMessage(content, JsonValue.Object(members))
        }
    }

    private companion object {
        val LOGPROBS = JsonValue.Object(mapOf("logprobs" to JsonValue.Null))
        private val log = LoggerFactory.getLogger(AnthropicResponseReader::class.java)
    }
}

/** The OpenAI members a reply and a stream both build. */
internal object ReplyMembers {
    /** One `tool_calls` item in full; on a stream [arguments] is `""` until the deltas arrive. */
    fun whole(
        id: String,
        name: String,
        arguments: String,
    ): JsonValue.Object =
        JsonValue.Object(
            mapOf(
                "id" to JsonValue.Text(id),
                "type" to JsonValue.Text("function"),
                "function" to
                    JsonValue.Object(
                        mapOf("name" to JsonValue.Text(name), "arguments" to JsonValue.Text(arguments)),
                    ),
            ),
        )

    /** `llmgw.thinking_blocks`, verbatim and in order (ADR-GW-03-011). */
    fun thinkingExtension(blocks: List<JsonValue>): JsonValue.Object =
        JsonValue.Object(mapOf("thinking_blocks" to JsonValue.Array(blocks)))
}
