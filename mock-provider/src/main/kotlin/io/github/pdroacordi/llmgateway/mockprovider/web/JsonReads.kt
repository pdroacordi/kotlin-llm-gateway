package io.github.pdroacordi.llmgateway.mockprovider.web

import io.github.pdroacordi.llmgateway.mockprovider.error.reject
import tools.jackson.databind.JsonNode

/** A member that is missing or JSON `null` is absent; one of the wrong type is a 400 naming its `param`. */
fun JsonNode.member(name: String): JsonNode? = get(name)?.takeUnless { it.isNull || it.isMissingNode }

fun JsonNode.optString(
    name: String,
    param: String = name,
): String? =
    member(name)?.let { node ->
        if (!node.isString) reject(param, "'$param' must be a string.")
        node.stringValue()
    }

fun JsonNode.requireString(
    name: String,
    param: String = name,
): String = optString(name, param) ?: reject(param, "'$param' is required.")

fun JsonNode.optBoolean(
    name: String,
    param: String = name,
): Boolean? =
    member(name)?.let { node ->
        if (!node.isBoolean) reject(param, "'$param' must be a boolean.")
        node.booleanValue()
    }

/** An integer member within [range]. */
fun JsonNode.optInt(
    name: String,
    range: IntRange,
    param: String = name,
): Int? =
    member(name)?.let { node ->
        if (!node.isIntegralNumber || !node.canConvertToInt() || node.intValue() !in range) {
            reject(param, "'$param' must be an integer from ${range.first} to ${range.last}.")
        }
        node.intValue()
    }

/** An integer member within [range], as a Long. */
fun JsonNode.optLong(
    name: String,
    range: LongRange,
    param: String = name,
): Long? =
    member(name)?.let { node ->
        if (!node.isIntegralNumber || !node.canConvertToLong() || node.longValue() !in range) {
            reject(param, "'$param' must be an integer from ${range.first} to ${range.last}.")
        }
        node.longValue()
    }

fun JsonNode.requireObject(param: String): JsonNode {
    if (!isObject) reject(param, "'$param' must be a JSON object.")
    return this
}

/** A non-empty array member. */
fun JsonNode.requireNonEmptyArray(
    name: String,
    param: String = name,
): List<JsonNode> {
    val node = member(name) ?: reject(param, "'$param' is required.")
    if (!node.isArray || node.size() == 0) reject(param, "'$param' must be a non-empty array.")
    return node.values().toList()
}
