package io.github.pdroacordi.llmgateway.gateway.core.json

/**
 * A JSON value with no library behind it, so core can carry members it does not model (ADR-018). Values can hold prompt
 * text, tool arguments or a whole image, so no `toString` here prints content.
 */
sealed interface JsonValue {
    data object Null : JsonValue

    data class Bool(
        val value: Boolean,
    ) : JsonValue

    /** [literal] is the number as JSON text, so a value keeps its precision through the gateway. */
    data class Number(
        val literal: String,
    ) : JsonValue

    data class Text(
        val value: String,
    ) : JsonValue {
        override fun toString(): String = "JsonValue.Text(length=${value.length})"
    }

    data class Array(
        val items: List<JsonValue>,
    ) : JsonValue {
        override fun toString(): String = "JsonValue.Array(items=${items.size})"
    }

    /** Insertion-ordered. */
    data class Object(
        val members: Map<String, JsonValue>,
    ) : JsonValue {
        override fun toString(): String = "JsonValue.Object(members=${members.size})"

        companion object {
            val EMPTY = Object(emptyMap())
        }
    }
}
