package io.github.pdroacordi.llmgateway.mockprovider.sse

/**
 * Provider-exact SSE frames, a space after each colon as the providers send them (Spring's `ServerSentEvent` writes
 * none; ADR-FND-03-007). Compact JSON never holds a newline, and one would split the event, so it is rejected.
 */
object SseFrames {
    fun openAiData(json: String): String = "data: ${checked(json)}\n\n"

    /** The sentinel that ends an OpenAI stream. */
    const val OPENAI_DONE = "data: [DONE]\n\n"

    fun anthropicEvent(
        type: String,
        json: String,
    ): String = "event: ${checked(type)}\ndata: ${checked(json)}\n\n"

    private fun checked(value: String): String {
        require('\n' !in value && '\r' !in value) { "an SSE field value must not contain a line break" }
        return value
    }
}
