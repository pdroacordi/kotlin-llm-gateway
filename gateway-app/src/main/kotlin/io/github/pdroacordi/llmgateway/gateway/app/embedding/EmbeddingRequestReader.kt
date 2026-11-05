package io.github.pdroacordi.llmgateway.gateway.app.embedding

import io.github.pdroacordi.llmgateway.gateway.app.error.GatewayErrorException
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingInput
import io.github.pdroacordi.llmgateway.gateway.core.embedding.EmbeddingRequest
import io.github.pdroacordi.llmgateway.gateway.core.error.GatewayError
import io.github.pdroacordi.llmgateway.gateway.providers.json.extrasExcept
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Request body to [EmbeddingRequest]. Refuses only what it cannot type; every other rule is the provider's
 * (ADR-GW-05-009). The checks run in the order of GW-05 section 4, and the first failure answers.
 */
object EmbeddingRequestReader {
    private val REQUEST_MEMBERS = setOf("model", "input")

    fun read(body: ObjectNode): EmbeddingRequest {
        val model =
            body
                .get("model")
                ?.takeIf { it.isString }
                ?.stringValue()
                ?.takeIf { it.isNotBlank() }
                ?: reject(GatewayError.InvalidRequest.modelRequired)
        val input = input(body.get("input")) ?: reject(GatewayError.InvalidRequest.inputRequired)
        return EmbeddingRequest(ModelName(model), input, body.extrasExcept(REQUEST_MEMBERS))
    }

    private fun input(node: JsonNode?): EmbeddingInput? =
        when {
            node == null -> null
            node.isString -> EmbeddingInput.Text(node.stringValue())
            node.isArray -> items(node.values().toList())
            else -> null
        }

    private fun items(items: List<JsonNode>): EmbeddingInput? =
        when {
            items.all { it.isString } -> EmbeddingInput.Texts(items.map { it.stringValue() })
            items.all { it.isTokenId() } -> EmbeddingInput.TokenIds(tokenIds(items))
            items.all { it.isTokenIdArray() } -> tokenIdLists(items)
            else -> null
        }

    private fun tokenIdLists(items: List<JsonNode>): EmbeddingInput =
        EmbeddingInput.TokenIdLists(items.map { tokenIds(it.values().toList()) })

    private fun tokenIds(items: List<JsonNode>): LongArray = LongArray(items.size) { items[it].longValue() }

    private fun JsonNode.isTokenId(): Boolean = isIntegralNumber && canConvertToLong()

    private fun JsonNode.isTokenIdArray(): Boolean = isArray && values().all { it.isTokenId() }

    private fun reject(error: GatewayError.InvalidRequest): Nothing = throw GatewayErrorException(error)
}
