package io.github.pdroacordi.llmgateway.gateway.providers.sse

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class SseEventParserTest {
    private fun parser(limit: Int = LIMIT) = SseEventParser(limit)

    private fun events(
        vararg feeds: String,
        limit: Int = LIMIT,
    ): List<SseEvent> {
        val parser = parser(limit)
        return feeds.flatMap { parser.feed(it.encodeToByteArray()).events }
    }

    private fun datas(vararg feeds: String): List<String> = events(*feeds).map { it.data.decodeToString() }

    @ParameterizedTest
    @ValueSource(strings = ["\n", "\r\n", "\r"])
    fun `every line ending gives the same events`(end: String) {
        assertEquals(listOf("a", "b"), datas("data: a${end}${end}data: b$end$end"))
    }

    @Test
    fun `mixed line endings give the same events`() {
        assertEquals(listOf("a", "b", "c"), datas("data: a\r\n\ndata: b\r\rdata: c\n\r\n"))
    }

    @Test
    fun `CR at the end of a buffer and LF at the start of the next are one line break`() {
        assertEquals(listOf("a\nb"), datas("data: a\r", "\ndata: b\r", "\n\r", "\n"))
    }

    @Test
    fun `every split point of a stream with multi-byte text gives the same events`() {
        val whole = STREAM.encodeToByteArray()
        val expected = parser().feed(whole).events.map { it.event to it.data.decodeToString() }

        for (split in 0..whole.size) {
            val parser = parser()
            val parts = listOf(whole.copyOfRange(0, split), whole.copyOfRange(split, whole.size))
            val actual = parts.flatMap { parser.feed(it).events }.map { it.event to it.data.decodeToString() }
            assertEquals(expected, actual) { "split at $split" }
        }
    }

    @Test
    fun `one byte at a time gives the same events`() {
        val whole = STREAM.encodeToByteArray()
        val expected = parser().feed(whole).events.map { it.event to it.data.decodeToString() }
        val parser = parser()

        val actual = whole.flatMap { parser.feed(byteArrayOf(it)).events }.map { it.event to it.data.decodeToString() }

        assertEquals(expected, actual)
    }

    @Test
    fun `a 4-byte UTF-8 character across buffers keeps its bytes`() {
        val character = "😀".encodeToByteArray()
        val parser = parser()

        val first = parser.feed("data: ".encodeToByteArray() + character.copyOfRange(0, 2))
        val second = parser.feed(character.copyOfRange(2, 4) + "\n\n".encodeToByteArray())

        assertTrue(first.events.isEmpty())
        assertArrayEquals(character, second.events.single().data)
    }

    @Test
    fun `one leading BOM is stripped`() {
        val parser = parser()

        val feed = parser.feed(BOM + "data: a\n\n".encodeToByteArray())

        assertEquals(
            "a",
            feed.events
                .single()
                .data
                .decodeToString(),
        )
    }

    @Test
    fun `a BOM split across buffers is stripped`() {
        val parser = parser()

        parser.feed(BOM.copyOfRange(0, 1))
        parser.feed(BOM.copyOfRange(1, 2))
        val feed = parser.feed(BOM.copyOfRange(2, 3) + "data: a\n\n".encodeToByteArray())

        assertEquals(
            "a",
            feed.events
                .single()
                .data
                .decodeToString(),
        )
    }

    @Test
    fun `only the first BOM is stripped`() {
        val parser = parser()

        val feed = parser.feed(BOM + BOM + "data: a\n\n".encodeToByteArray())

        // The second is part of an unknown field's name, so the line is ignored.
        assertTrue(feed.events.isEmpty())
    }

    @Test
    fun `bytes that start like a BOM and stop are kept`() {
        val start = BOM.copyOfRange(0, 2)
        val parser = parser()

        val feed = parser.feed(start + ": comment\ndata: a\n\n".encodeToByteArray())

        assertEquals(
            "a",
            feed.events
                .single()
                .data
                .decodeToString(),
        )
    }

    @Test
    fun `comments are ignored`() {
        assertEquals(listOf("a"), datas(": comment\n:\ndata: a\n: another\n\n"))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        ignoreLeadingAndTrailingWhitespace = false,
        value = ["data:x|x", "data: x|x", "data:  x| x"],
    )
    fun `one space after the colon is removed`(
        line: String,
        expected: String,
    ) {
        assertEquals(listOf(expected), datas("$line\n\n"))
    }

    @Test
    fun `two data lines join with one LF`() {
        assertEquals(listOf("a\nb"), datas("data: a\ndata: b\n\n"))
    }

    @Test
    fun `an event name is kept and the other fields are ignored`() {
        val event = events("event: a\nid: 7\nretry: 100\nunknown: x\nnocolon\ndata: b\n\n").single()

        assertEquals("a", event.event)
        assertEquals("b", event.data.decodeToString())
    }

    @Test
    fun `an event with no name has a null name, and a name does not reach the next event`() {
        val parsed = events("event: a\ndata: 1\n\ndata: 2\n\n")

        assertEquals("a", parsed[0].event)
        assertNull(parsed[1].event)
    }

    @Test
    fun `a blank line with no data gives no event and drops the name`() {
        val parsed = events("\n\nevent: a\n\ndata: b\n\n")

        assertNull(parsed.single().event)
    }

    @Test
    fun `an empty data line dispatches empty data, as WHATWG says`() {
        assertEquals(listOf(""), datas("data\n\n"))
        assertEquals(listOf("\n"), datas("data\ndata\n\n"))
    }

    @Test
    fun `EOF in mid-event dispatches nothing`() {
        assertTrue(events("data: a\n").isEmpty())
        assertTrue(events("data: a").isEmpty())
    }

    @Test
    fun `a line exactly at the limit passes`() {
        val line = "data: " + "x".repeat(LIMIT - "data: ".length)

        val feed = parser().feed("$line\n\n".encodeToByteArray())

        assertFalse(feed.overflowed)
        assertEquals(
            LIMIT - "data: ".length,
            feed.events
                .single()
                .data.size,
        )
    }

    @Test
    fun `a line one byte over the limit overflows at once, with no end of line`() {
        val feed = parser().feed(("data: " + "x".repeat(LIMIT)).encodeToByteArray())

        assertTrue(feed.overflowed)
        assertTrue(feed.events.isEmpty())
    }

    @Test
    fun `an event exactly at the limit passes, counted over its lines`() {
        val half = LIMIT / 2
        val first = "data: " + "x".repeat(half - "data: ".length)
        val second = "data: " + "y".repeat(LIMIT - half - "data: ".length)

        val feed = parser().feed("$first\n$second\n\n".encodeToByteArray())

        assertFalse(feed.overflowed)
        assertEquals(1, feed.events.size)
    }

    @Test
    fun `an event one byte over the limit overflows, and the count restarts after each event`() {
        val half = LIMIT / 2
        val first = "data: " + "x".repeat(half - "data: ".length)
        val second = "data: " + "y".repeat(LIMIT - half - "data: ".length + 1)
        val parser = parser()

        assertFalse(parser.feed("$first\n\n$first\n\n".encodeToByteArray()).overflowed)
        assertTrue(parser.feed("$first\n$second\n\n".encodeToByteArray()).overflowed)
    }

    @Test
    fun `events before an overflow are kept, and an overflowed parser gives nothing more`() {
        val parser = parser()

        val feed = parser.feed(("data: a\n\ndata: " + "x".repeat(LIMIT) + "\n\n").encodeToByteArray())
        val after = parser.feed("data: b\n\n".encodeToByteArray())

        assertEquals(
            "a",
            feed.events
                .single()
                .data
                .decodeToString(),
        )
        assertTrue(feed.overflowed)
        assertTrue(after.overflowed)
        assertTrue(after.events.isEmpty())
    }

    @Test
    fun `an event prints sizes only`() {
        val event = events("event: delta\ndata: $CANARY\n\n").single()

        assertFalse(CANARY in event.toString()) { event.toString() }
        assertEquals("SseEvent(event=delta, bytes=${CANARY.length})", event.toString())
    }

    private companion object {
        const val LIMIT = 64
        const val CANARY = "canary-text"
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        const val STREAM =
            "\uFEFF: comment\r\nevent: delta\r\ndata: {\"t\":\"é😀\"}\r\n\r\n" +
                "data: one\ndata: two\n\ndata: três\r\r:\rdata: [DONE]\n\n"
    }
}
