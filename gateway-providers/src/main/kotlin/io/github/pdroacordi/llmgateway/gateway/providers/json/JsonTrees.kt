package io.github.pdroacordi.llmgateway.gateway.providers.json

import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import tools.jackson.databind.cfg.JsonNodeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Every tree a gateway body is read into: a float stays a decimal, so a value is not rounded to a double on the way
 * through (ADR-018), and only one JSON value is accepted.
 */
fun JsonMapper.exactTreeReader(): ObjectReader =
    reader()
        .with(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

/**
 * Null when [body] is not JSON. Jackson raises a bare NumberFormatException for a decimal past BigDecimal's range; no
 * message is kept, because both exceptions quote the input.
 */
fun ObjectReader.readTreeOrNull(body: ByteArray): JsonNode? =
    try {
        readTree(body)
    } catch (_: JacksonException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

fun JsonNode.toJsonValue(): JsonValue =
    when {
        isNull -> JsonValue.Null
        isBoolean -> JsonValue.Bool(booleanValue())
        isNumber -> JsonValue.Number(asString())
        isString -> JsonValue.Text(stringValue())
        isArray -> JsonValue.Array(values().map { it.toJsonValue() })
        isObject -> JsonValue.Object(properties().associate { (name, value) -> name to value.toJsonValue() })
        else -> error("A $nodeType node is not JSON a body can hold.")
    }

fun JsonValue.toJsonNode(nodes: JsonNodeFactory): JsonNode =
    when (this) {
        JsonValue.Null -> {
            nodes.nullNode()
        }

        is JsonValue.Bool -> {
            nodes.booleanNode(value)
        }

        is JsonValue.Number -> {
            if (literal.none { it in FLOAT_MARKS }) {
                nodes.numberNode(BigInteger(literal))
            } else {
                nodes.numberNode(BigDecimal(literal))
            }
        }

        is JsonValue.Text -> {
            nodes.stringNode(value)
        }

        is JsonValue.Array -> {
            nodes.arrayNode(items.size).also { array -> items.forEach { array.add(it.toJsonNode(nodes)) } }
        }

        is JsonValue.Object -> {
            nodes.objectNode().also { node ->
                members.forEach { (name, value) -> node.set(name, value.toJsonNode(nodes)) }
            }
        }
    }

private const val FLOAT_MARKS = ".eE"
