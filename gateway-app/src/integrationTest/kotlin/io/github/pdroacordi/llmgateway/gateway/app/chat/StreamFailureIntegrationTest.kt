package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource
import java.time.Duration

// The opening chunk and two pieces.
private const val CHUNKS_BEFORE_THE_FAULT = 3

private fun assertOneErrorFrame(
    test: MockBackedTest,
    stream: RawStream,
    code: String,
    output: CapturedOutput,
    mark: Int,
) {
    assertEquals(200, stream.status)
    assertTrue(stream.cleanEnd) { stream.body }
    assertFalse("[DONE]" in stream.body) { stream.body }
    assertEquals(CHUNKS_BEFORE_THE_FAULT, stream.frames.size - 1) { stream.body }
    val error = test.json.readTree(stream.frames.last().removePrefix("data: "))
    assertEquals(setOf("error"), error.propertyNames().toSet())
    assertEquals(setOf("message", "type", "param", "code"), error.get("error").propertyNames().toSet())
    assertEquals(code, error.at("/error/code").stringValue())
    assertTrue(stream.requestId in error.at("/error/message").stringValue())
    await().atMost(Duration.ofSeconds(2)).until { "Stream failed" in output.all.substring(mark) }
    val log = output.all.substring(mark)
    val errors = log.lines().filter { " ERROR " in it }
    val line = errors.single { stream.requestId in it }
    assertTrue("Stream failed" in line) { line }
    assertFalse("Error finishing response" in log) { output.all }
    assertFalse(errors.any { "GatewayErrorWebExceptionHandler" in it }) { output.all }
}

@ExtendWith(OutputCaptureExtension::class)
class StreamFailureIntegrationTest : MockBackedTest() {
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"fault":{"kind":"disconnect","afterPieces":2}} | upstream_error""",
            """{"fault":{"kind":"malformed","afterPieces":2}} | upstream_error""",
            """{"error":{"status":500},"fault":{"kind":"stream_error","afterPieces":2}} | upstream_error""",
        ],
    )
    fun `a fault after the first frame ends in one error frame, no DONE and a clean end`(
        behaviour: String,
        code: String,
        output: CapturedOutput,
    ) {
        scenario("""{"otherwise":$behaviour}""")

        assertOneErrorFrame(this, rawStream(), code, output, mark)
    }
}

// A deadline of its own context: a 500 ms idle-timeout would also cut the rows above on a slow runner.
@TestPropertySource(
    properties = [
        "llmgw.providers.openai.stream.idle-timeout=500ms",
        "llmgw.server.client-write-timeout=400ms",
    ],
)
@ExtendWith(OutputCaptureExtension::class)
class StreamStallDeadlineTest : MockBackedTest() {
    private var mark = 0

    // Skips the class-level capture: docs/knowledge/gotchas/output-capture-all-includes-the-class-level-capture.md
    @BeforeEach
    fun markLog(output: CapturedOutput) {
        mark = output.length
    }

    @Test
    fun `stall at idle-timeout ends in one upstream_timeout frame`(output: CapturedOutput) {
        scenario("""{"otherwise":{"fault":{"kind":"stall","afterPieces":2}}}""")

        assertOneErrorFrame(this, rawStream(), "upstream_timeout", output, mark)
    }
}

// 3 s, not 1: the context's first request on a cold JVM must fit inside it.
@TestPropertySource(
    properties = [
        "llmgw.providers.openai.stream.first-byte-timeout=3s",
        "llmgw.providers.openai.stream.idle-timeout=3s",
        "llmgw.providers.openai.stream.total-timeout=3s",
        "llmgw.server.client-write-timeout=1s",
    ],
)
class StreamTotalDeadlineTest : MockBackedTest() {
    @Test
    fun `total-timeout cuts a stream that never goes idle`() {
        val words = "word ".repeat(WORDS)
        scenario("""{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"$words"}}}""")

        val stream = rawStream()

        assertEquals(200, stream.status)
        assertTrue(stream.cleanEnd) { stream.body }
        assertEquals(
            "upstream_timeout",
            mapper.readTree(stream.frames.last().removePrefix("data: ")).at("/error/code").stringValue(),
        )
        eventually {
            journalOf(stream.requestId).singleOrNull()?.at("/outcome/kind")?.stringValue() == "client_cancelled"
        }
    }

    private companion object {
        const val WORDS = 300
    }
}

@TestPropertySource(
    properties = [
        "llmgw.providers.openai.stream.max-event-size=1KB",
        "llmgw.providers.openai.max-response-size=1KB",
    ],
)
class StreamSizeLimitTest : MockBackedTest() {
    @Test
    fun `an event over max-event-size gives upstream_error after the frames so far`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"${"x".repeat(LARGE_WORD)}"}}}""")

        val stream = rawStream()

        assertEquals(200, stream.status)
        assertTrue(stream.cleanEnd) { stream.body }
        assertEquals(
            "upstream_error",
            mapper.readTree(stream.frames.last().removePrefix("data: ")).at("/error/code").stringValue(),
        )
    }

    @Test
    fun `a stream longer in total than max-response-size completes`() {
        scenario("""{"otherwise":{"content":{"mode":"fixed","text":"${"word ".repeat(WORDS)}"}}}""")

        val stream = rawStream()

        assertEquals("data: [DONE]", stream.frames.last())
        assertTrue(stream.body.length > MAX_RESPONSE_SIZE) { stream.body.length.toString() }
    }

    private companion object {
        const val LARGE_WORD = 2048
        const val WORDS = 50
        const val MAX_RESPONSE_SIZE = 1024
    }
}
