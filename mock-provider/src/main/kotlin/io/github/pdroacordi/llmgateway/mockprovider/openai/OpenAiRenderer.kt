package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlan
import io.github.pdroacordi.llmgateway.mockprovider.generation.Embedder
import io.github.pdroacordi.llmgateway.mockprovider.generation.Finish
import io.github.pdroacordi.llmgateway.mockprovider.generation.PlanPart
import io.github.pdroacordi.llmgateway.mockprovider.generation.PlannedUsage
import io.github.pdroacordi.llmgateway.mockprovider.sse.SseFrames
import io.github.pdroacordi.llmgateway.mockprovider.web.ErrorBody
import io.github.pdroacordi.llmgateway.mockprovider.web.Piece
import io.github.pdroacordi.llmgateway.mockprovider.web.Reply
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Locale

private fun functionCall(
    target: ObjectNode,
    seq: Long,
    index: Int,
    call: PlanPart.ToolCall,
    arguments: String,
) {
    target.put("id", call.id ?: "call_mock_${seq}_$index").put("type", "function")
    target.putObject("function").put("name", call.name).put("arguments", arguments)
}

/**
 * OpenAI bodies as explicit trees: member presence (`usage` absent versus null) and order are part of the contract
 * (FND-03 section 4; ADR-FND-03-010). Output is compact JSON.
 */
class OpenAiRenderer(
    private val mapper: JsonMapper,
) {
    fun completion(
        seq: Long,
        created: Long,
        model: String,
        plan: CompletionPlan,
    ): String {
        val root = header(seq, "chat.completion", created, model)
        val choice = root.putArray("choices").addObject()
        choice.put("index", 0)
        val message = choice.putObject("message").put("role", "assistant")
        if (plan.text.isEmpty() && plan.toolCalls.isNotEmpty()) {
            message.putNull("content")
        } else {
            message.put("content", plan.text)
        }
        if (plan.toolCalls.isNotEmpty()) {
            val calls = message.putArray("tool_calls")
            plan.toolCalls.forEachIndexed { index, call ->
                functionCall(calls.addObject(), seq, index, call, call.inputJson)
            }
        }
        message.putNull("refusal").putArray("annotations")
        choice.putNull("logprobs")
        choice.put("finish_reason", finishReason(plan.finish))
        if (!plan.usage.omit) root.set("usage", usage(plan.usage))
        root.put("service_tier", "default")
        root.put("system_fingerprint", FINGERPRINT)
        return mapper.writeValueAsString(root)
    }

    /** One chunk per piece; with [includeUsage], every chunk has `"usage":null` and a last chunk carries the usage. */
    fun stream(
        seq: Long,
        created: Long,
        model: String,
        plan: CompletionPlan,
        includeUsage: Boolean,
    ): Reply.Stream {
        fun chunk(fill: (ObjectNode) -> Unit): String {
            val root = header(seq, "chat.completion.chunk", created, model)
            root.put("system_fingerprint", FINGERPRINT)
            fill(root)
            if (includeUsage && !root.has("usage")) root.putNull("usage")
            return SseFrames.openAiData(mapper.writeValueAsString(root))
        }

        fun choice(
            root: ObjectNode,
            finish: String?,
            delta: (ObjectNode) -> Unit,
        ) {
            val choice = root.putArray("choices").addObject()
            choice.put("index", 0)
            delta(choice.putObject("delta"))
            choice.putNull("logprobs")
            if (finish == null) choice.putNull("finish_reason") else choice.put("finish_reason", finish)
        }

        fun toolChunk(
            index: Int,
            fill: (ObjectNode) -> Unit,
        ): String =
            chunk { root ->
                choice(root, null) { delta -> fill(delta.putArray("tool_calls").addObject().put("index", index)) }
            }

        val opening =
            chunk { root ->
                choice(root, null) { delta ->
                    delta.put("role", "assistant")
                    if (plan.parts.any { it is PlanPart.Text }) delta.put("content", "") else delta.putNull("content")
                }
            }
        val pieces = mutableListOf<Piece>()
        val pending = mutableListOf<String>()
        plan.parts.filterIsInstance<PlanPart.Text>().forEach { text ->
            text.pieces.forEach { piece ->
                pieces +=
                    Piece(chunk { root -> choice(root, null) { it.put("content", piece) } })
            }
        }
        plan.toolCalls.forEachIndexed { index, call ->
            // OpenAI sends an empty input as arguments, and has no empty `partial_json` to stand for it.
            val arguments = if (call.pieces.isEmpty()) call.inputJson else ""
            pending += toolChunk(index) { functionCall(it, seq, index, call, arguments) }
            call.pieces.forEach { piece ->
                val frame = toolChunk(index) { it.putObject("function").put("arguments", piece) }
                pieces += Piece(pending + frame)
                pending.clear()
            }
        }
        val closing =
            buildList {
                addAll(pending)
                add(chunk { root -> choice(root, finishReason(plan.finish)) {} })
                if (includeUsage && !plan.usage.omit) {
                    add(
                        chunk { root ->
                            root.putArray("choices")
                            root.set("usage", usage(plan.usage))
                        },
                    )
                }
                add(SseFrames.OPENAI_DONE)
            }
        return Reply.Stream(listOf(opening), pieces, closing, SseFrames.openAiData(MALFORMED_FRAGMENT), ::streamError)
    }

    /** [promptTokens] null omits `usage` (scenario `usage.omit`). */
    fun embeddings(
        model: String,
        vectors: List<FloatArray>,
        base64: Boolean,
        promptTokens: Int?,
    ): String {
        val root = mapper.createObjectNode()
        root.put("object", "list")
        val data = root.putArray("data")
        vectors.forEachIndexed { index, vector ->
            val item = data.addObject().put("object", "embedding").put("index", index)
            if (base64) {
                item.put("embedding", Embedder.base64(vector))
            } else {
                val values = item.putArray("embedding")
                vector.forEach { values.add(it) }
            }
        }
        root.put("model", model)
        promptTokens?.let { root.putObject("usage").put("prompt_tokens", it).put("total_tokens", it) }
        return mapper.writeValueAsString(root)
    }

    fun models(
        ids: List<String>,
        created: Long,
    ): String {
        val root = mapper.createObjectNode()
        root.put("object", "list")
        val data = root.putArray("data")
        ids.forEach { id ->
            data
                .addObject()
                .put("id", id)
                .put("object", "model")
                .put("created", created)
                .put("owned_by", "mock-provider")
        }
        return mapper.writeValueAsString(root)
    }

    /** The four-member envelope: `param` and `code` always present, possibly null. */
    fun error(body: ErrorBody): String = mapper.writeValueAsString(envelope(body))

    /** openai-python raises on a data event whose `error` member is set; no `[DONE]` follows (FND-03 section 4). */
    private fun streamError(body: ErrorBody): String = SseFrames.openAiData(mapper.writeValueAsString(envelope(body)))

    private fun envelope(body: ErrorBody): ObjectNode {
        val root = mapper.createObjectNode()
        root
            .putObject("error")
            .put("message", body.message)
            .put("type", body.type)
            .put("param", body.param)
            .put("code", body.code)
        return root
    }

    private fun header(
        seq: Long,
        objectType: String,
        created: Long,
        model: String,
    ): ObjectNode =
        mapper
            .createObjectNode()
            .put("id", "chatcmpl-mock-%012d".format(Locale.ROOT, seq))
            .put("object", objectType)
            .put("created", created)
            .put("model", model)

    private fun usage(usage: PlannedUsage): ObjectNode {
        val node =
            mapper
                .createObjectNode()
                .put("prompt_tokens", usage.promptTokens)
                .put("completion_tokens", usage.completionTokens)
                .put("total_tokens", usage.totalTokens)
        node.putObject("prompt_tokens_details").put("cached_tokens", usage.cachedTokens)
        node.putObject("completion_tokens_details").put("reasoning_tokens", 0)
        return node
    }

    private fun finishReason(finish: Finish): String =
        when (finish) {
            Finish.STOP, Finish.STOP_SEQUENCE -> "stop"
            Finish.LENGTH, Finish.PAUSE_TURN, Finish.CONTEXT_WINDOW -> "length"
            Finish.TOOL_CALLS -> "tool_calls"
            Finish.REFUSAL -> "content_filter"
        }

    companion object {
        const val FINGERPRINT = "fp_mock"

        /** The non-stream `malformed` body; not JSON on purpose. */
        const val MALFORMED_BODY = """{"id": "chatcmpl-mock"""
        private const val MALFORMED_FRAGMENT = "{not json"
    }
}
