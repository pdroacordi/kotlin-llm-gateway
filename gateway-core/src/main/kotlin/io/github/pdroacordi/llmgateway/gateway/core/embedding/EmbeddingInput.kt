package io.github.pdroacordi.llmgateway.gateway.core.embedding

/**
 * The four forms of `input`, kept apart because a token array holds no text (ADR-GW-05-001). No `toString` here prints
 * content, so a logged object cannot carry an input (ADR-GW-05-008).
 */
sealed interface EmbeddingInput {
    class Text(
        val text: String,
    ) : EmbeddingInput {
        override fun toString(): String = "EmbeddingInput.Text(length=${text.length})"
    }

    /** Empty when the client sent `[]`, which goes upstream (ADR-GW-05-009). */
    class Texts(
        val texts: List<String>,
    ) : EmbeddingInput {
        override fun toString(): String = "EmbeddingInput.Texts(items=${texts.size})"
    }

    class TokenIds(
        val ids: LongArray,
    ) : EmbeddingInput {
        override fun toString(): String = "EmbeddingInput.TokenIds(items=${ids.size})"
    }

    class TokenIdLists(
        val lists: List<LongArray>,
    ) : EmbeddingInput {
        override fun toString(): String = "EmbeddingInput.TokenIdLists(items=${lists.size})"
    }
}
