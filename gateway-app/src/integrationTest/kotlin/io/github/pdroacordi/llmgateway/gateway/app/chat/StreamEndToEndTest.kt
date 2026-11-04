package io.github.pdroacordi.llmgateway.gateway.app.chat

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatExtensions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import java.net.Socket
import java.nio.charset.StandardCharsets

class StreamEndToEndTest : MockBackedTest() {
    private fun data(frame: String): JsonNode = mapper.readTree(frame.removePrefix("data: "))

    private fun chunks(stream: RawStream): List<JsonNode> = stream.frames.dropLast(1).map(::data)

    private fun upstreamBody(requestId: String): JsonNode =
        mapper.readTree(journalOf(requestId).single().at("/body").stringValue())

    @Test
    fun `a stream gives 200 with every commit header, chunked and with no length`() {
        val stream = rawStream()

        assertEquals(200, stream.status)
        assertEquals("text/event-stream;charset=UTF-8", stream.header("content-type"))
        assertEquals("no-cache", stream.header("cache-control"))
        assertEquals("no", stream.header("x-accel-buffering"))
        assertEquals("openai", stream.header("x-gateway-deployment"))
        assertEquals("chunked", stream.header("transfer-encoding"))
        assertEquals('7', stream.requestId[VERSION_INDEX])
        assertNull(stream.header("content-length"))
        assertNull(stream.header("x-gateway-usage-estimated"))
        assertTrue(stream.cleanEnd)
    }

    @Test
    fun `each frame on the wire is data, compact JSON and a blank line, and the last is DONE`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"Hi there"}}}""")

        val stream = rawStream()

        assertEquals("data: [DONE]", stream.frames.last())
        assertTrue(stream.body.endsWith("data: [DONE]\n\n"))
        assertEquals(stream.frames.joinToString("") { "$it\n\n" }, stream.body)
        chunks(stream).zip(stream.frames).forEach { (tree, frame) -> assertEquals("data: $tree", frame) }
    }

    @Test
    fun `there is exactly one journal entry, and upstream stream and include_usage are true`() {
        val stream = rawStream()

        val body = upstreamBody(stream.requestId)
        assertTrue(body.get("stream").booleanValue())
        assertTrue(body.at("/stream_options/include_usage").booleanValue())
        assertEquals("completed", journalOf(stream.requestId).single().at("/outcome/kind").stringValue())
    }

    @Test
    fun `without include_usage no event has usage, none has empty choices, and usage is still asked upstream`() {
        val stream = rawStream(asking(includeUsage = false))

        chunks(stream).forEach { chunk ->
            assertFalse(chunk.has("usage")) { chunk.toString() }
            assertFalse(chunk.get("choices").isEmpty) { chunk.toString() }
        }
        assertTrue(upstreamBody(stream.requestId).at("/stream_options/include_usage").booleanValue())
    }

    @Test
    fun `a client's other stream_options member reaches the mock unchanged`() {
        val body = STREAM.dropLast(1) + ""","stream_options":{"include_obfuscation":false}}"""

        val stream = rawStream(body)

        val options = upstreamBody(stream.requestId).get("stream_options")
        assertFalse(options.get("include_obfuscation").booleanValue())
        assertTrue(options.get("include_usage").booleanValue())
    }

    @Test
    fun `provider usage on a stream has no extension member`() {
        val last = chunks(rawStream(asking())).last()

        assertTrue(last.get("choices").isEmpty)
        assertTrue(last.at("/usage/prompt_tokens").intValue() > 0)
        assertFalse(last.has(ChatExtensions.MEMBER)) { last.toString() }
    }

    @Test
    fun `usage omit with include_usage gives a flagged usage chunk from the estimator`() {
        scenario("""{"otherwise":{"usage":{"omit":true},"content":{"mode":"fixed","text":"12345678"}}}""")

        val stream = rawStream(asking())

        val last = chunks(stream).last()
        assertTrue(last.get("choices").isEmpty) { last.toString() }
        assertEquals(2, last.at("/usage/completion_tokens").intValue())
        assertTrue(last.at("/${ChatExtensions.MEMBER}/usage_estimated").booleanValue())
        assertEquals("data: [DONE]", stream.frames.last())
    }

    @Test
    fun `usage omit without include_usage gives no usage chunk`() {
        scenario("""{"otherwise":{"usage":{"omit":true}}}""")

        val chunks = chunks(rawStream(asking(includeUsage = false)))

        assertTrue(chunks.none { it.get("choices").isEmpty })
        assertTrue(chunks.none { it.has(ChatExtensions.MEMBER) })
    }

    @Test
    fun `Accept application json on a stream still gives SSE`() {
        val stream =
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val headers = "Accept: application/json\r\nConnection: close\r\n"
                socket.getOutputStream().write(rawRequest(STREAM, headers).encodeToByteArray())
                RawStream.of(socket.getInputStream().readAllBytes())
            }

        assertEquals(200, stream.status)
        assertEquals("text/event-stream;charset=UTF-8", stream.header("content-type"))
    }

    @Test
    fun `the first and last content events reach a client at least 600 ms apart at perTokenMs 200`() {
        scenario(
            """{"otherwise":{"latency":{"perTokenMs":200},
               "content":{"mode":"fixed","text":"one two three four five"}}}""",
        )

        val arrivals = mutableListOf<Long>()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            socket.getOutputStream().write(rawRequest(STREAM, "Connection: close\r\n").encodeToByteArray())
            val lines = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
            generateSequence { lines.readLine() }
                .filter { it.startsWith("data: ") && """"content":"""" in it && """"content":""""" !in it }
                .forEach { _ -> arrivals += System.nanoTime() }
        }

        assertTrue(arrivals.size >= PIECES) { arrivals.size.toString() }
        assertTrue((arrivals.last() - arrivals.first()) / NANOS_PER_MS >= SPREAD_MS) { arrivals.toString() }
    }

    @ParameterizedTest
    @MethodSource("rejections")
    fun `each stream_options row gives its envelope, and no call reaches the mock`(
        members: String,
        message: String,
        param: String,
    ) {
        val before = lastSeq()
        val body = """{"model":"mock-chat","messages":[{"role":"user","content":"Hi"}],$members}"""

        val result = chat(body).result()

        assertEquals(400, result.status.value())
        assertEquals(
            mapper.readTree(
                """{"error":{"message":"$message","type":"invalid_request_error","param":"$param",""" +
                    """"code":"invalid_request"}}""",
            ),
            mapper.readTree(result.responseBody),
        )
        assertEquals(before, lastSeq())
    }

    companion object {
        private const val VERSION_INDEX = 14
        private const val PIECES = 5
        private const val SPREAD_MS = 600
        private const val NANOS_PER_MS = 1_000_000

        @JvmStatic
        fun rejections(): List<Arguments> =
            listOf(
                Arguments.of(
                    """"stream":true,"stream_options":5""",
                    "'stream_options' must be an object.",
                    "stream_options",
                ),
                Arguments.of(
                    """"stream":true,"stream_options":{"include_usage":"x"}""",
                    "'include_usage' must be a boolean.",
                    "stream_options.include_usage",
                ),
                Arguments.of(
                    """"stream_options":{}""",
                    "'stream_options' is only allowed when 'stream' is true.",
                    "stream_options",
                ),
            )
    }
}
