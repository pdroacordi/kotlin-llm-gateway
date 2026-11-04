package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlan
import io.github.pdroacordi.llmgateway.mockprovider.generation.Finish
import io.github.pdroacordi.llmgateway.mockprovider.generation.PlanPart
import io.github.pdroacordi.llmgateway.mockprovider.sse.SseFrames
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBody
import io.github.pdroacordi.llmgateway.mockprovider.web.Reply
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Locale

/** Anthropic Messages bodies and the streaming event sequence of its streaming guide (FND-03 section 4). */
class AnthropicRenderer(
    private val mapper: JsonMapper,
) {
    private val frames = AnthropicFrames(mapper)

    fun message(
        seq: Long,
        model: String,
        plan: CompletionPlan,
    ): String {
        val root = messageHeader(seq, model)
        val content = root.putArray("content")
        var toolIndex = 0
        plan.parts.forEach { part ->
            when (part) {
                is PlanPart.Thinking -> {
                    content.add(frames.thinkingBlock(part, part.signature.orEmpty()))
                }

                is PlanPart.Text -> {
                    val text = part.pieces.joinToString("")
                    if (text.isNotEmpty()) content.addObject().put("type", "text").put("text", text)
                }

                is PlanPart.ToolCall -> {
                    content.add(frames.toolUseBlock(seq, toolIndex++, part, mapper.readTree(part.inputJson)))
                }
            }
        }
        root.put("stop_reason", stopReason(plan.finish))
        root.putNull("stop_sequence")
        if (!plan.usage.omit) {
            val usage = inputUsage(root.putObject("usage"), plan)
            usage
                .putObject("cache_creation")
                .put("ephemeral_5m_input_tokens", plan.usage.cacheCreationTokens)
                .put("ephemeral_1h_input_tokens", 0)
            usage.put("output_tokens", plan.usage.completionTokens)
            usage.put("service_tier", "standard")
        }
        return mapper.writeValueAsString(root)
    }

    /**
     * `message_delta.usage` carries only the cumulative `output_tokens`, like the documented example; input and cache
     * counts are in `message_start`, which reports `output_tokens: 1`. Blocks follow the plan's parts; frames with no
     * piece of their own ride on the next piece, or go to [Reply.Stream.opening] before the first.
     */
    fun stream(
        seq: Long,
        model: String,
        plan: CompletionPlan,
    ): Reply.Stream {
        val start = messageHeader(seq, model)
        start.putArray("content")
        start.putNull("stop_reason")
        start.putNull("stop_sequence")
        if (!plan.usage.omit) inputUsage(start.putObject("usage"), plan).put("output_tokens", 1)

        val blocks = frames.blockFrames(seq, plan)
        val opening =
            buildList {
                add(frames.event("message_start") { it.set("message", start) })
                addAll(blocks.beforeFirstPiece)
                add(frames.event("ping") {})
            }
        val closing =
            blocks.afterLastPiece +
                listOf(
                    frames.event("message_delta") { root ->
                        root.putObject("delta").put("stop_reason", stopReason(plan.finish)).putNull("stop_sequence")
                        if (!plan.usage.omit) root.putObject("usage").put("output_tokens", plan.usage.completionTokens)
                    },
                    frames.event("message_stop") {},
                )
        return Reply.Stream(opening, blocks.pieces, closing, MALFORMED_EVENT, ::streamError)
    }

    fun error(
        body: ErrorBody,
        requestId: String,
    ): String {
        val root = errorRoot(body)
        root.put("request_id", requestId)
        return mapper.writeValueAsString(root)
    }

    private fun streamError(body: ErrorBody): String =
        SseFrames.anthropicEvent("error", mapper.writeValueAsString(errorRoot(body)))

    private fun errorRoot(body: ErrorBody): ObjectNode {
        val root = mapper.createObjectNode().put("type", "error")
        val error = root.putObject("error").put("type", body.type).put("message", body.message)
        body.code?.let { error.putObject("details").put("error_code", it) }
        return root
    }

    private fun messageHeader(
        seq: Long,
        model: String,
    ): ObjectNode =
        mapper
            .createObjectNode()
            .put("id", "msg_mock_%012d".format(Locale.ROOT, seq))
            .put("type", "message")
            .put("role", "assistant")
            .put("model", model)

    // input + cache read + cache creation = the OpenAI prompt count of the same conversation (FND-03 section 5, Usage).
    private fun inputUsage(
        usage: ObjectNode,
        plan: CompletionPlan,
    ): ObjectNode =
        usage
            .put("input_tokens", plan.usage.anthropicInputTokens)
            .put("cache_creation_input_tokens", plan.usage.cacheCreationTokens)
            .put("cache_read_input_tokens", plan.usage.cachedTokens)

    private fun stopReason(finish: Finish): String =
        when (finish) {
            Finish.STOP -> "end_turn"
            Finish.LENGTH -> "max_tokens"
            Finish.TOOL_CALLS -> "tool_use"
            Finish.STOP_SEQUENCE -> "stop_sequence"
            Finish.REFUSAL -> "refusal"
            Finish.PAUSE_TURN -> "pause_turn"
            Finish.CONTEXT_WINDOW -> "model_context_window_exceeded"
        }

    companion object {
        /** The non-stream `malformed` body; not JSON on purpose. */
        const val MALFORMED_BODY = """{"id": "msg_mock"""
        private const val MALFORMED_EVENT = "event: content_block_delta\ndata: {not json\n\n"
    }
}
