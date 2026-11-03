package io.github.pdroacordi.llmgateway.mockprovider.generation

/** A content part as the request parsers see it; only text counts for generation and usage. */
sealed interface ContentPart {
    data class Text(
        val text: String,
    ) : ContentPart

    /** Anthropic `tool_result`: its own text parts count. */
    data class ToolResult(
        val parts: List<ContentPart>,
    ) : ContentPart

    /** Images, documents, tool calls and anything else: accepted, ignored (FND-03 section 2, Out). */
    data object Other : ContentPart
}

data class Turn(
    val role: String,
    val text: String,
) {
    companion object {
        fun openAi(
            role: String,
            content: String?,
        ): Turn = Turn(role, content.orEmpty())

        fun openAi(
            role: String,
            parts: List<ContentPart>,
        ): Turn = Turn(role, texts(parts).joinToString("\n"))

        fun anthropic(
            role: String,
            blocks: List<ContentPart>,
        ): Turn = Turn(role, texts(blocks).joinToString("\n"))

        private fun texts(parts: List<ContentPart>): List<String> =
            parts.flatMap { part ->
                when (part) {
                    is ContentPart.Text -> listOf(part.text)
                    is ContentPart.ToolResult -> texts(part.parts)
                    ContentPart.Other -> emptyList()
                }
            }
    }
}

/**
 * The text that drives generation: the model and the turns, nothing else, so the stream and non-stream forms of a
 * request, and the same conversation on either API, give the same content (FND-03 section 5, Generation).
 */
data class Conversation(
    val model: String,
    val turns: List<Turn>,
) {
    fun canonical(): String =
        buildString {
            append(model)
            turns.forEach { append(SEPARATOR).append(it.role).append(SEPARATOR).append(it.text) }
        }

    fun seed(): Long = Fnv1a.hash64(canonical())

    fun lastUserText(): String? = turns.lastOrNull { it.role == "user" }?.text

    private companion object {
        const val SEPARATOR = '\u0000'
    }
}
