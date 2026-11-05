package io.github.pdroacordi.llmgateway.gateway.providers.json

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class JsonMembersTest {
    private val trees = JsonMapper.builder().build().exactTreeReader()

    // Each root member's value as the source spelled it.
    private fun ranges(body: ByteArray): Map<String, String>? =
        JsonMembers.scan(trees, body) { scan ->
            buildMap { scan.forEachMember { name -> put(name, scan.raw().decodeToString()) } }
        }

    private fun ranges(json: String): Map<String, String>? = ranges(json.encodeToByteArray())

    @Test
    fun `whitespace around a value is outside its range, and inside it is kept`() {
        assertEquals(
            mapOf("a" to "[ 1 ,\n2 ]", "b" to "3", "c" to "\"x\""),
            ranges("""{ "a" :  [ 1 ,""" + "\n" + """2 ]  , "b" :  3  , "c" :  "x"  }"""),
        )
    }

    @Test
    fun `a string with escapes is its full literal`() {
        assertEquals(
            mapOf("a" to """"x\"y\\zé"""", "b" to "1"),
            ranges("""{"a":"x\"y\\zé","b":1}"""),
        )
    }

    @Test
    fun `a multi-byte string keeps later ranges right`() {
        assertEquals(mapOf("a" to "\"é€😀\"", "b" to "[1]"), ranges("""{"a":"é€😀","b":[1]}"""))
    }

    @Test
    fun `nested containers are one range`() {
        assertEquals(
            mapOf("a" to """[[1,[2]],{"b":[3]}]""", "c" to """{"d":{"e":[]}}""", "f" to "true"),
            ranges("""{"a":[[1,[2]],{"b":[3]}],"c":{"d":{"e":[]}},"f":true}"""),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["1e-5", "-0.10", "0", "1E+2", "123456789012345678901234567890", "true", "false", "null"])
    fun `a scalar at the end of an object is exact`(literal: String) {
        assertEquals(mapOf("a" to literal), ranges("""{"a":$literal}"""))
        assertEquals(mapOf("a" to literal), ranges("""{"a":$literal }"""))
    }

    @Test
    fun `a number past BigDecimal's range is only lexed`() {
        assertEquals(mapOf("a" to "[1e99999999999]"), ranges("""{"a":[1e99999999999]}"""))
    }

    @Test
    fun `a BOM does not shift a range`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = bom + """{"a":[1],"b":"c"}""".encodeToByteArray()

        assertEquals(mapOf("a" to "[1]", "b" to "\"c\""), ranges(body))
    }

    @Test
    fun `a UTF-16 body has no byte offsets, so it is unreadable`() {
        assertNull(ranges("""{"a":[1]}""".toByteArray(Charsets.UTF_16LE)))
        assertNull(ranges("""{"a":[1]}""".toByteArray(Charsets.UTF_16)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["{}x", "{} {}", "{}]", """{"a":1} 2"""])
    fun `a trailing token after the root is unreadable`(json: String) {
        assertNull(ranges(json))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "{",
            """{"a":""",
            """{"a":[1""",
            """{"a":"x""",
            """{"a":[1,]}""",
            """{"a":nul}""",
            """{"a":[1 2]}""",
        ],
    )
    fun `a body that is not well formed is unreadable, also inside a skipped array`(json: String) {
        assertNull(ranges(json))
    }

    @Test
    fun `a number over Jackson's length limit is unreadable, with no exception`() {
        assertNull(ranges("""{"a":[${"1".repeat(1_100)}]}"""))
    }

    @Test
    fun `items of an array are walked in order`() {
        val seen =
            JsonMembers.scan(trees, """{"a":[{"b":1},{"b":2}]}""".encodeToByteArray()) { scan ->
                buildList {
                    scan.forEachMember {
                        scan.forEachItem { scan.forEachMember { add(scan.raw().decodeToString()) } }
                    }
                }
            }

        assertEquals(listOf("1", "2"), seen)
    }

    @Test
    fun `an int and a text are read, and another type is null`() {
        val body = """{"i":7,"big":2147483648,"f":1.0,"s":"é","n":null}""".encodeToByteArray()
        val read =
            JsonMembers.scan(trees, body) { scan ->
                buildMap<String, Any?> {
                    scan.forEachMember { name ->
                        put(name, if (scan.atString) scan.text() else scan.int())
                    }
                }
            }

        assertEquals(mapOf("i" to 7, "big" to null, "f" to null, "s" to "é", "n" to null), read)
    }

    @Test
    fun `a skipped member is not read`() {
        val names =
            JsonMembers.scan(trees, """{"a":[1,{"b":2}],"c":"d","e":3}""".encodeToByteArray()) { scan ->
                buildList {
                    scan.forEachMember { name ->
                        add(name)
                        scan.skip()
                    }
                }
            }

        assertEquals(listOf("a", "c", "e"), names)
    }

    @Test
    fun `unreadable ends the scan with null`() {
        assertNull(JsonMembers.scan<Unit>(trees, "{}".encodeToByteArray()) { it.unreadable() })
    }
}
