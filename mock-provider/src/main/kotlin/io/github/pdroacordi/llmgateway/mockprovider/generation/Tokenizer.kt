package io.github.pdroacordi.llmgateway.mockprovider.generation

/**
 * The mock's token approximation (ADR-FND-03-005): a token is a run of letters or digits, or one other non-space
 * code point. Not a real tokenizer; it makes one streamed piece exactly one token.
 */
object Tokenizer {
    private val TOKEN = Regex("""[\p{L}\p{N}]+|[^\s\p{L}\p{N}]""")

    fun count(text: String): Int = TOKEN.findAll(text).count()

    /** Each token with the whitespace before it; trailing whitespace joins the last piece, so they join to [text]. */
    fun pieces(text: String): List<String> {
        val matches = TOKEN.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val start = if (index == 0) 0 else matches[index - 1].range.last + 1
            val end = if (index == matches.lastIndex) text.length else match.range.last + 1
            text.substring(start, end)
        }
    }
}
