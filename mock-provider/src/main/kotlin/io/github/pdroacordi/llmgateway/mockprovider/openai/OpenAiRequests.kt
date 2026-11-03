package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import io.github.pdroacordi.llmgateway.mockprovider.generation.ContentPart
import io.github.pdroacordi.llmgateway.mockprovider.generation.Conversation
import io.github.pdroacordi.llmgateway.mockprovider.generation.EmbeddingInput
import io.github.pdroacordi.llmgateway.mockprovider.generation.Turn
import io.github.pdroacordi.llmgateway.mockprovider.web.member
import io.github.pdroacordi.llmgateway.mockprovider.web.optBoolean
import io.github.pdroacordi.llmgateway.mockprovider.web.optInt
import io.github.pdroacordi.llmgateway.mockprovider.web.optString
import io.github.pdroacordi.llmgateway.mockprovider.web.requireNonEmptyArray
import io.github.pdroacordi.llmgateway.mockprovider.web.requireString
import tools.jackson.databind.JsonNode

/** [cap] is the smaller of `max_completion_tokens` and the deprecated `max_tokens`, null when neither is set. */
data class ChatRequest(
    val model: String,
    val conversation: Conversation,
    val stream: Boolean,
    val includeUsage: Boolean,
    val cap: Int?,
)

data class EmbeddingsRequest(
    val model: String,
    val inputs: List<EmbeddingInput>,
    val dimensions: Int,
    val base64: Boolean,
)

/** `mock.embeddings.default-dimensions` and `max-dimensions`. */
data class EmbeddingLimits(
    val defaultDimensions: Int,
    val maxDimensions: Int,
)

/**
 * Reads the members of OpenAI requests the mock uses; everything else, `tools` and images included, is ignored
 * (FND-03 section 4). Validation failures are 400 `invalid_request_error` naming the member.
 */
object OpenAiRequests {
    private val NON_NEGATIVE = 0..Int.MAX_VALUE

    fun chat(body: JsonNode): ChatRequest {
        if (!body.isObject) reject(null, "The request body must be a JSON object.")
        val model = body.requireString("model")
        val messages = body.requireNonEmptyArray("messages")
        body.member("n")?.let { n ->
            if (!n.isIntegralNumber || n.longValue() != 1L) {
                reject("n", "mock-provider supports only n = 1.", code = "unsupported_value")
            }
        }
        val caps =
            listOfNotNull(body.optInt("max_completion_tokens", NON_NEGATIVE), body.optInt("max_tokens", NON_NEGATIVE))
        val streamOptions = body.member("stream_options")
        return ChatRequest(
            model = model,
            conversation = Conversation(model, messages.mapIndexed(::turn)),
            stream = body.optBoolean("stream") ?: false,
            includeUsage = streamOptions?.optBoolean("include_usage", "stream_options.include_usage") ?: false,
            cap = caps.minOrNull(),
        )
    }

    fun embeddings(
        body: JsonNode,
        limits: EmbeddingLimits,
    ): EmbeddingsRequest {
        if (!body.isObject) reject(null, "The request body must be a JSON object.")
        val model = body.requireString("model")
        val inputs = inputs(body.member("input") ?: reject("input", "'input' is required."))
        if (inputs.size > MAX_EMBEDDING_INPUTS) reject("input", "'input' holds at most $MAX_EMBEDDING_INPUTS items.")
        val dimensions = body.optInt("dimensions", 1..limits.maxDimensions) ?: limits.defaultDimensions
        val format = body.optString("encoding_format") ?: FLOAT
        if (format != FLOAT && format != BASE64) reject("encoding_format", "'encoding_format' must be float or base64.")
        return EmbeddingsRequest(model, inputs, dimensions, format == BASE64)
    }

    private fun turn(
        index: Int,
        message: JsonNode,
    ): Turn {
        val param = "messages[$index]"
        if (!message.isObject) reject(param, "'$param' must be a JSON object.")
        val role = message.requireString("role", "$param.role")
        val content = message.member("content")
        return when {
            content == null -> Turn.openAi(role, null as String?)
            content.isString -> Turn.openAi(role, content.stringValue())
            content.isArray -> Turn.openAi(role, content.values().map(::part))
            else -> reject("$param.content", "'$param.content' must be a string, an array or null.")
        }
    }

    private fun part(node: JsonNode): ContentPart {
        val text = node.member("text")
        return if (node.member("type")?.stringValue() == "text" && text != null && text.isString) {
            ContentPart.Text(text.stringValue())
        } else {
            ContentPart.Other
        }
    }

    // string | string[] | int[] | int[][], each non-empty.
    private fun inputs(input: JsonNode): List<EmbeddingInput> {
        val items = if (input.isArray) input.values().toList() else listOf(input)
        val parsed =
            when {
                !input.isArray && input.isString -> {
                    items.map { EmbeddingInput.Text(it.stringValue()) }
                }

                items.isEmpty() -> {
                    null
                }

                items.all { it.isString } -> {
                    items.map { EmbeddingInput.Text(it.stringValue()) }
                }

                items.all(::isTokenId) -> {
                    listOf(EmbeddingInput.Tokens(items.map { it.intValue() }))
                }

                items.all { it.isArray && it.size() > 0 && it.values().all(::isTokenId) } -> {
                    items.map { array -> EmbeddingInput.Tokens(array.values().map { it.intValue() }) }
                }

                else -> {
                    null
                }
            }
        if (parsed == null || parsed.any { it is EmbeddingInput.Text && it.text.isEmpty() }) {
            reject(
                "input",
                "'input' must be a non-empty string, array of strings, token array or array of token arrays.",
            )
        }
        return parsed
    }

    private fun isTokenId(node: JsonNode): Boolean = node.isIntegralNumber && node.canConvertToInt()

    // OpenAI's documented limit per request; also bounds the vectors one request makes.
    private const val MAX_EMBEDDING_INPUTS = 2048
    private const val FLOAT = "float"
    private const val BASE64 = "base64"
}
