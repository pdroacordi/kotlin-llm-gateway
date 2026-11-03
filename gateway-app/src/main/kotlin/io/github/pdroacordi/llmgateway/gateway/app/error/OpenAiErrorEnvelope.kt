package io.github.pdroacordi.llmgateway.gateway.app.error

import io.github.pdroacordi.llmgateway.gateway.core.error.ErrorReply

/**
 * The OpenAI error body: exactly one member, `error`, with four members always present, `param` and `code` possibly
 * null. openai-python reads `body["error"]` and then these four.
 */
data class OpenAiErrorEnvelope(
    val error: Body,
) {
    data class Body(
        val message: String,
        val type: String,
        val param: String?,
        val code: String?,
    )

    companion object {
        fun of(reply: ErrorReply): OpenAiErrorEnvelope =
            OpenAiErrorEnvelope(Body(reply.message, reply.type, reply.param, reply.code))
    }
}
