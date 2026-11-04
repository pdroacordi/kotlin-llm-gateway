package io.github.pdroacordi.llmgateway.gateway.app.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.test.context.TestPropertySource

// The mock's default `message`, so a relayed one would show.
private const val PROVIDER_MESSAGE = "mock-provider injected"

class AnthropicStreamFailureIntegrationTest : AnthropicBackedTest() {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"fault":{"kind":"disconnect","afterPieces":2}} | upstream_error""",
            """{"fault":{"kind":"malformed","afterPieces":2}} | upstream_error""",
            """{"error":{"status":529},"fault":{"kind":"stream_error","afterPieces":2}} | provider_unavailable""",
            """{"error":{"status":429},"fault":{"kind":"stream_error","afterPieces":2}} | upstream_rate_limited""",
        ],
    )
    fun `a fault after the first frame ends in one error frame, no DONE, a clean end and no provider message`(
        behaviour: String,
        code: String,
    ) {
        claudeScenario("""{"otherwise":$behaviour}""")

        val stream = rawStream(CLAUDE_STREAM)
        val error = mapper.readTree(stream.frames.last().removePrefix("data: "))

        assertEquals(200, stream.status)
        assertTrue(stream.cleanEnd) { stream.body }
        assertFalse("[DONE]" in stream.body) { stream.body }
        assertFalse(PROVIDER_MESSAGE in stream.body) { stream.body }
        assertEquals(CHUNKS_BEFORE_THE_FAULT, stream.frames.size - 1) { stream.body }
        assertEquals(setOf("error"), error.propertyNames().toSet())
        assertEquals(code, error.at("/error/code").stringValue())
    }

    private companion object {
        const val CHUNKS_BEFORE_THE_FAULT = 2
    }
}

// A deadline of its own context: a 500 ms idle-timeout would also cut the rows above on a slow runner.
@TestPropertySource(
    properties = [
        "llmgw.providers.anthropic.stream.idle-timeout=500ms",
        "llmgw.server.client-write-timeout=400ms",
    ],
)
class AnthropicStreamStallDeadlineTest : AnthropicBackedTest() {
    @org.junit.jupiter.api.Test
    fun `stall at idle-timeout after the first frame gives an upstream_timeout frame`() {
        claudeScenario("""{"otherwise":{"fault":{"kind":"stall","afterPieces":2}}}""")

        val stream = rawStream(CLAUDE_STREAM)

        assertEquals(200, stream.status)
        assertEquals(
            "upstream_timeout",
            mapper
                .readTree(stream.frames.last().removePrefix("data: "))
                .at("/error/code")
                .stringValue(),
        )
    }
}

// first-byte and idle must not exceed total, and the write timeout stays below idle.
@TestPropertySource(
    properties = [
        "llmgw.providers.anthropic.stream.total-timeout=1s",
        "llmgw.providers.anthropic.stream.first-byte-timeout=1s",
        "llmgw.providers.anthropic.stream.idle-timeout=1s",
        "llmgw.server.client-write-timeout=500ms",
    ],
)
class AnthropicStreamTotalDeadlineTest : AnthropicBackedTest() {
    @org.junit.jupiter.api.Test
    fun `the total deadline cuts a stream that never goes idle`() {
        val words = "word ".repeat(WORDS)
        claudeScenario("""{"otherwise":{"latency":{"perTokenMs":100},"content":{"mode":"fixed","text":"$words"}}}""")

        val stream = rawStream(CLAUDE_STREAM)

        assertEquals(200, stream.status)
        assertEquals(
            "upstream_timeout",
            mapper
                .readTree(stream.frames.last().removePrefix("data: "))
                .at("/error/code")
                .stringValue(),
        )
    }

    private companion object {
        const val WORDS = 200
    }
}
