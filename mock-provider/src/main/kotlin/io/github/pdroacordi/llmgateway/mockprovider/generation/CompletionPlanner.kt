package io.github.pdroacordi.llmgateway.mockprovider.generation

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedContent
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedUsage

enum class Finish {
    /** OpenAI `stop`, Anthropic `end_turn`. */
    STOP,

    /** The cap cut the content: OpenAI `length`, Anthropic `max_tokens`. */
    LENGTH,
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

/** One completion: its streamed pieces (one token each), their concatenation, why it ended and its usage. */
data class CompletionPlan(
    val pieces: List<String>,
    val finish: Finish,
    val usage: PlannedUsage,
) {
    val text: String get() = pieces.joinToString("")
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
        val prompt = usage.promptTokens ?: PromptTokens.of(conversation)
        val cached = minOf(usage.cachedTokens, prompt)
        return CompletionPlan(
            pieces = pieces,
            finish = if (cut || (maxTokens == 0)) Finish.LENGTH else Finish.STOP,
            usage =
                PlannedUsage(
                    promptTokens = prompt,
                    completionTokens = usage.completionTokens ?: pieces.size,
                    cachedTokens = cached,
                    cacheCreationTokens = minOf(usage.cacheCreationTokens, prompt - cached),
                    omit = usage.omit,
                ),
        )
    }
}
