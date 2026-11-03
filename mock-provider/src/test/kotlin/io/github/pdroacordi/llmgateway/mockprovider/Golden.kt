package io.github.pdroacordi.llmgateway.mockprovider

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedContent
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ResolvedUsage
import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlan
import io.github.pdroacordi.llmgateway.mockprovider.generation.CompletionPlanner
import io.github.pdroacordi.llmgateway.mockprovider.generation.Conversation
import io.github.pdroacordi.llmgateway.mockprovider.generation.TextGenerator
import io.github.pdroacordi.llmgateway.mockprovider.generation.Turn

/** The conditions of the golden files in `src/test/resources/golden` (FND-03 section 3). */
object Golden {
    const val CREATED = 1_793_750_400L
    const val SEQ = 1L
    const val PROMPT = "Please greet the mock provider in two words"
    const val TEXT = "Hi there"

    /** `{"content":{"mode":"fixed","text":"Hi there"}}` as a scenario `otherwise`. */
    const val FIXED_SCENARIO = """{"otherwise":{"content":{"mode":"fixed","text":"$TEXT"}}}"""

    fun read(name: String): String =
        checkNotNull(Golden::class.java.getResource("/golden/$name")) { "missing golden/$name" }
            .readBytes()
            .decodeToString()

    fun plan(model: String): CompletionPlan =
        CompletionPlanner(TextGenerator()).plan(
            Conversation(model, listOf(Turn("user", PROMPT))),
            null,
            ResolvedContent(ContentMode.FIXED, TEXT, 16),
            ResolvedUsage(omit = false, cachedTokens = 0, cacheCreationTokens = 0),
        )

    fun chatRequest(
        stream: Boolean,
        includeUsage: Boolean = false,
    ): String {
        val options = if (includeUsage) ""","stream_options":{"include_usage":true}""" else ""
        return """{"model":"mock-chat","stream":$stream$options,"messages":[{"role":"user","content":"$PROMPT"}]}"""
    }

    fun messagesRequest(stream: Boolean): String =
        """{"model":"mock-claude","max_tokens":256,"stream":$stream,"messages":[{"role":"user","content":"$PROMPT"}]}"""
}
