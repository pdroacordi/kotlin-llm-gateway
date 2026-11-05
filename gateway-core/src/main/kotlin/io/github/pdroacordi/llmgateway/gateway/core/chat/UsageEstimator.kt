package io.github.pdroacordi.llmgateway.gateway.core.chat

import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput

// OpenAI's rule of thumb and token-counting cookbook, as in FND-03 (ADR-GW-01-011).
private const val CHARACTERS_PER_TOKEN = 4
private const val TOKENS_PER_MESSAGE = 3L
private const val TOKENS_PER_REPLY = 3L

/** The count used when a provider omits usage; a lower bound when opaque parts such as images are present. */
class UsageEstimator {
    /** No per-message overhead: an embedding call has no messages, and its reply has no tokens (ADR-GW-05-007). */
    fun estimate(input: EmbeddingInput): Usage {
        val tokens =
            when (input) {
                is EmbeddingInput.Text -> tokensIn(input.text)
                is EmbeddingInput.Texts -> input.texts.sumOf(::tokensIn)
                is EmbeddingInput.TokenIds -> input.ids.size.toLong()
                is EmbeddingInput.TokenIdLists -> input.lists.sumOf { it.size.toLong() }
            }
        return Usage(TokenCount(tokens), TokenCount(0), estimated = true)
    }

    fun estimate(
        request: ChatRequest,
        completion: ChatCompletion,
    ): Usage =
        estimate(
            request,
            completion.choices.map {
                it.message.content
                    .orEmpty()
                    .length
                    .toLong()
            },
        )

    /** [outputCharacters]: one count per choice, never the text, so a stream need not keep it. */
    fun estimate(
        request: ChatRequest,
        outputCharacters: Collection<Long>,
    ): Usage {
        val input = request.messages.sumOf { TOKENS_PER_MESSAGE + tokensIn(it.content) } + TOKENS_PER_REPLY
        val output = outputCharacters.sumOf(::tokensFor)
        return Usage(TokenCount(input), TokenCount(output), estimated = true)
    }

    private fun tokensIn(content: MessageContent?): Long =
        when (content) {
            null -> 0
            is MessageContent.Text -> tokensIn(content.text)
            is MessageContent.Parts -> content.parts.sumOf(::tokensIn)
        }

    private fun tokensIn(part: ContentPart): Long =
        when (part) {
            is ContentPart.Text -> tokensIn(part.text)
            is ContentPart.Opaque -> 0
        }

    private fun tokensIn(text: String): Long = tokensFor(text.length.toLong())

    private fun tokensFor(characters: Long): Long = (characters + CHARACTERS_PER_TOKEN - 1) / CHARACTERS_PER_TOKEN
}
