package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlan
import io.github.pdroacordi.llmgateway.mockprovider.generation.PlanPart
import io.github.pdroacordi.llmgateway.mockprovider.sse.SseFrames
import io.github.pdroacordi.llmgateway.mockprovider.web.Piece
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

internal class BlockFrames(
    val beforeFirstPiece: List<String>,
    val pieces: List<Piece>,
    val afterLastPiece: List<String>,
)

/** Content blocks and the stream events that carry them, in the plan's order (ADR-GW-03-019). */
internal class AnthropicFrames(
    private val mapper: JsonMapper,
) {
    fun event(
        type: String,
        fill: (ObjectNode) -> Unit,
    ): String {
        val root = mapper.createObjectNode().put("type", type)
        fill(root)
        return SseFrames.anthropicEvent(type, mapper.writeValueAsString(root))
    }

    fun blockFrames(
        seq: Long,
        plan: CompletionPlan,
    ): BlockFrames {
        val pieces = mutableListOf<Piece>()
        var opening = emptyList<String>()
        val pending = mutableListOf<String>()
        var stop = emptyList<String>()
        var toolIndex = 0

        fun piece(frame: String) {
            if (pieces.isEmpty()) {
                opening = pending.toList()
                pieces += Piece(frame)
            } else {
                pieces += Piece(pending + frame)
            }
            pending.clear()
        }

        plan.parts.forEachIndexed { index, part ->
            pending += stop
            when (part) {
                is PlanPart.Thinking -> {
                    pending += thinkingFrames(index, part)
                }

                is PlanPart.Text -> {
                    pending += blockStart(index, mapper.createObjectNode().put("type", "text").put("text", ""))
                    part.pieces.forEach { text ->
                        piece(delta(index) { it.put("type", "text_delta").put("text", text) })
                    }
                }

                is PlanPart.ToolCall -> {
                    val block = toolUseBlock(seq, toolIndex++, part, mapper.createObjectNode())
                    pending += blockStart(index, block)
                    pending += inputDelta(index, "")
                    part.pieces.forEach { piece(inputDelta(index, it)) }
                }
            }
            stop = listOf(event("content_block_stop") { it.put("index", index) })
        }
        return if (pieces.isEmpty()) {
            BlockFrames(pending.toList(), pieces, stop)
        } else {
            BlockFrames(opening, pieces, pending + stop)
        }
    }

    // `display: "omitted"` in the thinking guide: nothing but a signature; a redacted block arrives whole at its start.
    private fun thinkingFrames(
        index: Int,
        part: PlanPart.Thinking,
    ): List<String> {
        val signature = part.signature ?: return listOf(blockStart(index, thinkingBlock(part, "")))
        return listOf(
            blockStart(index, thinkingBlock(part, "")),
            delta(index) { it.put("type", "thinking_delta").put("thinking", "") },
            delta(index) { it.put("type", "signature_delta").put("signature", signature) },
        )
    }

    fun thinkingBlock(
        part: PlanPart.Thinking,
        signature: String,
    ): ObjectNode =
        if (part.redacted != null) {
            mapper.createObjectNode().put("type", "redacted_thinking").put("data", part.redacted)
        } else {
            mapper
                .createObjectNode()
                .put("type", "thinking")
                .put("thinking", "")
                .put("signature", signature)
        }

    fun toolUseBlock(
        seq: Long,
        toolIndex: Int,
        part: PlanPart.ToolCall,
        input: JsonNode,
    ): ObjectNode {
        val block =
            mapper
                .createObjectNode()
                .put("type", "tool_use")
                .put("id", part.id ?: "toolu_mock_${seq}_$toolIndex")
                .put("name", part.name)
        block.set("input", input)
        return block
    }

    private fun blockStart(
        index: Int,
        block: ObjectNode,
    ): String =
        event("content_block_start") { root ->
            root.put("index", index)
            root.set("content_block", block)
        }

    private fun delta(
        index: Int,
        fill: (ObjectNode) -> Unit,
    ): String = event("content_block_delta") { root -> fill(root.put("index", index).putObject("delta")) }

    private fun inputDelta(
        index: Int,
        json: String,
    ): String = delta(index) { it.put("type", "input_json_delta").put("partial_json", json) }
}
