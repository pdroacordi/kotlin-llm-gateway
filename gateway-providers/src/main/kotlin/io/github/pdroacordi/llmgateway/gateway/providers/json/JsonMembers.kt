package io.github.pdroacordi.llmgateway.gateway.providers.json

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonParser
import tools.jackson.core.JsonToken
import tools.jackson.databind.ObjectReader

/**
 * One streaming walk over a body, where a value is copied as its byte range and never built (ADR-GW-05-005,
 * docs/knowledge/patterns/opaque-json-value-as-byte-range.md). The only use of Jackson's streaming API. Each visit must
 * end a value with exactly one of [raw], [skip], [text], [int], [forEachMember] or [forEachItem].
 */
internal class JsonMembers private constructor(
    private val parser: JsonParser,
    private val body: ByteArray,
) {
    val atObject: Boolean get() = parser.currentToken() == JsonToken.START_OBJECT
    val atArray: Boolean get() = parser.currentToken() == JsonToken.START_ARRAY
    val atString: Boolean get() = parser.currentToken() == JsonToken.VALUE_STRING

    fun forEachMember(visit: (String) -> Unit) {
        if (!atObject) unreadable()
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            val name = parser.currentName()
            parser.nextToken()
            visit(name)
        }
    }

    fun forEachItem(visit: () -> Unit) {
        if (!atArray) unreadable()
        while (parser.nextToken() != JsonToken.END_ARRAY) visit()
    }

    /** The value's bytes as the source wrote them. */
    fun raw(): ByteArray {
        val start = parser.currentTokenLocation().byteOffset
        // A string token is incomplete until finished: the location would stop after its opening quote.
        if (atString) parser.finishToken() else parser.skipChildren()
        val end = parser.currentLocation().byteOffset
        // -1 on a body Jackson reads as UTF-16 or UTF-32.
        if (start < 0 || end < 0) unreadable()
        return body.copyOfRange(start.toInt(), end.toInt())
    }

    fun skip() {
        parser.skipChildren()
    }

    /** Null when the value is not a string. */
    fun text(): String? = if (atString) parser.getString() else null.also { skip() }

    /** Null when the value is not an integer in `Int` range. */
    fun int(): Int? =
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT &&
            parser.numberType == JsonParser.NumberType.INT
        ) {
            parser.intValue
        } else {
            null.also { skip() }
        }

    /** Ends the scan: [scan] returns null. */
    fun unreadable(): Nothing = throw Unreadable()

    private class Unreadable : RuntimeException(null, null, false, false)

    companion object {
        /**
         * [read] starts on the root value. Null when [body] is not one well-formed JSON value or [read] gave up.
         * Jackson's exceptions quote the input, so no message is kept.
         */
        fun <T : Any> scan(
            trees: ObjectReader,
            body: ByteArray,
            read: (JsonMembers) -> T,
        ): T? =
            try {
                trees.createParser(body).use { parser ->
                    parser.nextToken()
                    val result = read(JsonMembers(parser, body))
                    // A walk that stops at the root's end would accept `{}garbage`.
                    result.takeIf { parser.nextToken() == null }
                }
            } catch (_: Unreadable) {
                null
            } catch (_: JacksonException) {
                null
            } catch (_: NumberFormatException) {
                null
            }
    }
}
