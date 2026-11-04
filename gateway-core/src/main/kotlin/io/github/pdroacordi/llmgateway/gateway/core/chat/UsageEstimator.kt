package io.github.pdroacordi.llmgateway.gateway.core.chat

// OpenAI's rule of thumb and token-counting cookbook, as in FND-03 (ADR-GW-01-011).
private const val CHARACTERS_PER_TOKEN = 4
private const val TOKENS_PER_MESSAGE = 3L
private const val TOKENS_PER_REPLY = 3L

/** The count used when a provider omits usage; a lower bound when opaque parts such as images are present. */
class UsageEstimator {
    fun estimate(
        request: ChatRequest,
        completion: ChatCompletion,
    ): Usage {
        val input = request.messages.sumOf { TOKENS_PER_MESSAGE + tokensIn(it.content) } + TOKENS_PER_REPLY
        val output = completion.choices.sumOf { tokensIn(it.message.content.orEmpty()) }
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

    private fun tokensIn(text: String): Long {
        val rounded = text.length.toLong() + CHARACTERS_PER_TOKEN - 1
        return rounded / CHARACTERS_PER_TOKEN
    }
}
