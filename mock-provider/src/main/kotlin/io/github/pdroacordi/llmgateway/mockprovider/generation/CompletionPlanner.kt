package io.github.pdroacordi.llmgateway.mockprovider.generation

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedContent
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedUsage
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScriptedFinish
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ToolCallSpec

enum class Finish {
    /** OpenAI `stop`, Anthropic `end_turn`. */
    STOP,

    /** The cap cut the content: OpenAI `length`, Anthropic `max_tokens`. */
    LENGTH,

    /** OpenAI `tool_calls`, Anthropic `tool_use`. */
    TOOL_CALLS,

    /** OpenAI `stop`, Anthropic `stop_sequence`. */
    STOP_SEQUENCE,

    /** OpenAI `content_filter`, Anthropic `refusal`. */
    REFUSAL,

    /** OpenAI `length`, Anthropic `pause_turn`. */
    PAUSE_TURN,

    /** OpenAI `length`, Anthropic `model_context_window_exceeded`. */
    CONTEXT_WINDOW,
}

/**
 * Reported usage, in approximate tokens (ADR-FND-03-005). [cachedTokens] and [cacheCreationTokens] are already clamped
 * to [promptTokens]; OpenAI reports cached tokens as a subset of the prompt, Anthropic splits the prompt in three.
 */
data class PlannedUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val cacheCreationTokens: Int,
    val omit: Boolean,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    val anthropicInputTokens: Int get() = promptTokens - cachedTokens - cacheCreationTokens
}

/** One part of a reply, rendered per provider (ADR-GW-03-019). Each `pieces` is what a stream sends one at a time. */
sealed interface PlanPart {
    /** Exactly one is set: a `thinking` block's [signature], or a `redacted_thinking` block's [redacted] data. */
    data class Thinking(
        val signature: String?,
        val redacted: String?,
    ) : PlanPart

    data class Text(
        val pieces: List<String>,
    ) : PlanPart

    /** [id] null takes the renderer's default. [pieces] is empty for an empty input: Anthropic sends no argument. */
    data class ToolCall(
        val id: String?,
        val name: String,
        val inputJson: String,
        val pieces: List<String>,
    ) : PlanPart
}

/** One completion: its parts, why it ended and its usage. */
data class CompletionPlan(
    val parts: List<PlanPart>,
    val finish: Finish,
    val usage: PlannedUsage,
) {
    val text: String get() = parts.filterIsInstance<PlanPart.Text>().joinToString("") { it.pieces.joinToString("") }

    val toolCalls: List<PlanPart.ToolCall> get() = parts.filterIsInstance<PlanPart.ToolCall>()

    /** Text and argument pieces, in order. */
    val pieces: List<String>
        get() =
            parts.flatMap { part ->
                when (part) {
                    is PlanPart.Text -> part.pieces
                    is PlanPart.ToolCall -> part.pieces
                    is PlanPart.Thinking -> emptyList()
                }
            }

    val pieceCount: Int get() = pieces.size
}

/** Prompt tokens: 3 per turn plus its tokens, plus 3 for the reply priming (OpenAI's token-counting cookbook). */
object PromptTokens {
    private const val PER_TURN = 3
    private const val REPLY_PRIMING = 3

    fun of(conversation: Conversation): Int =
        conversation.turns.sumOf { PER_TURN + Tokenizer.count(it.text) } + REPLY_PRIMING
}

class CompletionPlanner(
    private val textGenerator: TextGenerator,
) {
    /** [maxTokens] is the request's cap (OpenAI `max_completion_tokens`/`max_tokens`, Anthropic `max_tokens`). */
    fun plan(
        conversation: Conversation,
        maxTokens: Int?,
        content: ResolvedContent,
        usage: ResolvedUsage,
    ): CompletionPlan {
        val text =
            when (content.mode) {
                ContentMode.LOREM -> textGenerator.lorem(conversation.seed(), content.completionTokens)
                ContentMode.ECHO -> conversation.lastUserText().orEmpty()
                ContentMode.FIXED -> content.text.orEmpty()
            }
        val all = Tokenizer.pieces(text)
        val cut = maxTokens != null && all.size > maxTokens
        val pieces = if (cut) all.take(maxTokens) else all
        val tools = toolCalls(content.toolCalls, maxTokens?.let { it - pieces.size })
        val cutTools = tools.size < content.toolCalls.size
        val thinking = content.thinking.map { PlanPart.Thinking(it.signature, it.redacted) }
        val textPart = PlanPart.Text(pieces).takeIf { pieces.isNotEmpty() || (thinking.isEmpty() && tools.isEmpty()) }
        val parts = thinking + listOfNotNull(textPart) + tools
        val prompt = usage.promptTokens ?: PromptTokens.of(conversation)
        val cached = minOf(usage.cachedTokens, prompt)
        return CompletionPlan(
            parts = parts,
            finish = finish(content.finish, cut || cutTools || maxTokens == 0, tools.isNotEmpty()),
            usage =
                PlannedUsage(
                    promptTokens = prompt,
                    completionTokens = usage.completionTokens ?: (pieces.size + tools.sumOf { it.pieces.size }),
                    cachedTokens = cached,
                    cacheCreationTokens = minOf(usage.cacheCreationTokens, prompt - cached),
                    omit = usage.omit,
                ),
        )
    }

    // A call the cap would cut is left out with those after it, so the stream and non-stream forms still agree.
    private fun toolCalls(
        specs: List<ToolCallSpec>,
        tokensLeft: Int?,
    ): List<PlanPart.ToolCall> {
        var left = tokensLeft ?: Int.MAX_VALUE
        val planned = specs.map(::toolCall)
        return planned.takeWhile { call ->
            (call.pieces.size <= left).also { fits -> if (fits) left -= call.pieces.size }
        }
    }

    private fun toolCall(spec: ToolCallSpec): PlanPart.ToolCall {
        val pieces =
            if (spec.inputJson == EMPTY_INPUT) {
                emptyList()
            } else {
                spec.inputJson.chunked(spec.chunkChars ?: DEFAULT_CHUNK_CHARS)
            }
        return PlanPart.ToolCall(spec.id, spec.name, spec.inputJson, pieces)
    }

    private fun finish(
        scripted: ScriptedFinish?,
        capped: Boolean,
        toolCalled: Boolean,
    ): Finish =
        when {
            scripted != null -> scripted.toFinish()
            capped -> Finish.LENGTH
            toolCalled -> Finish.TOOL_CALLS
            else -> Finish.STOP
        }

    private fun ScriptedFinish.toFinish(): Finish =
        when (this) {
            ScriptedFinish.STOP_SEQUENCE -> Finish.STOP_SEQUENCE
            ScriptedFinish.REFUSAL -> Finish.REFUSAL
            ScriptedFinish.PAUSE_TURN -> Finish.PAUSE_TURN
            ScriptedFinish.CONTEXT_WINDOW -> Finish.CONTEXT_WINDOW
        }

    private companion object {
        const val EMPTY_INPUT = "{}"
        const val DEFAULT_CHUNK_CHARS = 8
    }
}
