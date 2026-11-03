package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlan
import io.github.pdroacordi.llmgateway.mockprovider.generation.Finish
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
    fun message(
        seq: Long,
        model: String,
        plan: CompletionPlan,
    ): String {
        val root = messageHeader(seq, model)
        val content = root.putArray("content")
        if (plan.text.isNotEmpty()) content.addObject().put("type", "text").put("text", plan.text)
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
     * counts are in `message_start`, which reports `output_tokens: 1`.
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

        val opening =
            listOf(
                event("message_start") { it.set("message", start) },
                event("content_block_start") { root ->
                    root
                        .put("index", 0)
                        .putObject("content_block")
                        .put("type", "text")
                        .put("text", "")
                },
                event("ping") {},
            )
        val pieces =
            plan.pieces.map { piece ->
                event("content_block_delta") { root ->
                    root
                        .put("index", 0)
                        .putObject("delta")
                        .put("type", "text_delta")
                        .put("text", piece)
                }
            }
        val closing =
            listOf(
                event("content_block_stop") { it.put("index", 0) },
                event("message_delta") { root ->
                    root.putObject("delta").put("stop_reason", stopReason(plan.finish)).putNull("stop_sequence")
                    if (!plan.usage.omit) root.putObject("usage").put("output_tokens", plan.usage.completionTokens)
                },
                event("message_stop") {},
            )
        return Reply.Stream(opening, pieces, closing, MALFORMED_EVENT, ::streamError)
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
        root.putObject("error").put("type", body.type).put("message", body.message)
        return root
    }

    private fun event(
        type: String,
        fill: (ObjectNode) -> Unit,
    ): String {
        val root = mapper.createObjectNode().put("type", type)
        fill(root)
        return SseFrames.anthropicEvent(type, mapper.writeValueAsString(root))
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
        }

    companion object {
        /** The non-stream `malformed` body; not JSON on purpose. */
        const val MALFORMED_BODY = """{"id": "msg_mock"""
        private const val MALFORMED_EVENT = "event: content_block_delta\ndata: {not json\n\n"
    }
}
