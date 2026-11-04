package io.github.pdroacordi.llmgateway.gateway.providers.anthropic

import io.github.pdroacordi.llmgateway.gateway.core.chat.ChatChunk
import io.github.pdroacordi.llmgateway.gateway.core.chat.ProviderStreamEvent
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.error.ProviderFailure
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import io.github.pdroacordi.llmgateway.gateway.providers.upstream.StreamLifecycle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AnthropicStreamDecoderTest {
    private val trees = JsonMapper.builder().build().exactTreeReader()
    private val clock = Clock.fixed(Instant.ofEpochSecond(CREATED), ZoneOffset.UTC)

    private fun lifecycle(heldLimit: Int = LIMIT): StreamLifecycle =
        StreamLifecycle(
            AnthropicStreamDecoder(trees, AnthropicFailureClassifier(trees), clock, heldLimit),
            LIMIT,
        )

    private val stream = lifecycle()

    private fun event(
        name: String,
        json: String,
    ): String = "event: $name\ndata: $json\n\n"

    private fun feed(vararg frames: String): List<ProviderStreamEvent> =
        stream.feed(frames.joinToString("").encodeToByteArray())

    private fun List<ProviderStreamEvent>.chunks(): List<ChatChunk> =
        filterIsInstance<ProviderStreamEvent.Chunk>().map { it.chunk }

    private fun ChatChunk.delta(name: String): JsonValue? =
        choices
            .single()
            .delta.extras.members[name]

    private fun ChatChunk.finish(): String? =
        choices
            .single()
            .finishReason
            ?.value

    private fun ChatChunk.thinkingBlocks(): List<JsonValue> =
        ((delta("llmgw") as JsonValue.Object).members["thinking_blocks"] as JsonValue.Array).items

    private fun ChatChunk.toolCall(): JsonValue.Object =
        (delta("tool_calls") as JsonValue.Array).items.single() as JsonValue.Object

    private fun JsonValue.Object.function(name: String): JsonValue? =
        (members["function"] as JsonValue.Object).members[name]

    @Test
    fun `message_start, ping and a text block start give nothing`() {
        assertTrue(feed(START, PING, TEXT_START, PING).isEmpty())
        assertFalse(stream.ended)
    }

    @Test
    fun `the documented text sequence gives chunks, a finish chunk, a usage chunk and Completed`() {
        val events = feed(START, TEXT_START, PING, text("Hi"), text(" there"), BLOCK_STOP, delta("end_turn", 5), STOP)
        val chunks = events.chunks()

        assertEquals(
            listOf("Hi", " there", null),
            chunks.take(3).map {
                it.choices
                    .single()
                    .delta.content
            },
        )
        assertEquals(JsonValue.Text("assistant"), chunks[0].delta("role"))
        assertNull(chunks[1].delta("role"))
        assertEquals(
            "stop",
            chunks[2]
                .choices
                .single()
                .finishReason
                ?.value,
        )
        assertTrue(chunks[3].choices.isEmpty())
        assertEquals(TokenCount(15), chunks[3].usage?.inputTokens)
        assertEquals(TokenCount(5), chunks[3].usage?.outputTokens)
        assertEquals(ProviderStreamEvent.Completed, events.last())
        assertTrue(stream.draining)
    }

    @Test
    fun `every chunk has one id, one created and the model, and each choice has logprobs`() {
        val chunks = feed(START, text("a"), text("b"), delta("end_turn", 1), STOP).chunks()

        assertEquals(setOf("msg_1"), chunks.map { it.id }.toSet())
        assertEquals(setOf(CREATED), chunks.map { it.created }.toSet())
        assertEquals(setOf("claude-x"), chunks.map { it.model.value }.toSet())
        chunks.filter { it.choices.isNotEmpty() }.forEach { chunk ->
            assertTrue(
                "logprobs" in
                    chunk.choices
                        .single()
                        .extras.members,
            )
        }
        assertEquals(
            JsonValue.Null,
            chunks[0]
                .choices
                .single()
                .extras.members["finish_reason"],
        )
    }

    @Test
    fun `empty text gives no chunk`() {
        assertTrue(feed(START, TEXT_START, text("")).isEmpty())
    }

    @Test
    fun `tool indexes count tools from 0 whatever the block index, and arguments concatenate`() {
        val chunks =
            feed(
                START,
                text("Checking"),
                toolStart(1, "t1"),
                json(1, ""),
                json(1, """{"a":"""),
                json(1, "1}"),
                blockStop(1),
                toolStart(2, "t2"),
                json(2, """{"b":2}"""),
                blockStop(2),
            ).chunks()
        val calls = chunks.drop(1).map { it.toolCall() }

        assertEquals(
            listOf("0", "0", "0", "1", "1"),
            calls.map { (it.members["index"] as JsonValue.Number).literal },
        )
        assertEquals(JsonValue.Text("t1"), calls[0].members["id"])
        assertEquals(JsonValue.Text("function"), calls[0].members["type"])
        assertEquals(JsonValue.Text("get"), calls[0].function("name"))
        assertEquals(JsonValue.Text(""), calls[0].function("arguments"))
        assertEquals(JsonValue.Text("""{"a":"""), calls[1].function("arguments"))
        assertEquals(JsonValue.Text("1}"), calls[2].function("arguments"))
    }

    @Test
    fun `a first chunk that is a tool chunk has role and null content`() {
        val first = feed(START, toolStart(0, "t")).chunks().single()

        assertEquals(JsonValue.Text("assistant"), first.delta("role"))
        assertEquals(JsonValue.Null, first.delta("content"))
    }

    @Test
    fun `a tool with no input ends with arguments of an empty object`() {
        val chunks = feed(START, toolStart(0, "t"), json(0, ""), blockStop(0)).chunks()

        assertEquals(2, chunks.size)
        assertEquals(JsonValue.Text("{}"), chunks[1].toolCall().function("arguments"))
    }

    @Test
    fun `thinking is held and comes back on the finish chunk, in order and whole`() {
        val before =
            feed(
                START,
                blockStart(0, THINKING_BLOCK),
                event("content_block_delta", delta(0, """{"type":"thinking_delta","thinking":"th"}""")),
                event("content_block_delta", delta(0, """{"type":"thinking_delta","thinking":"ink"}""")),
                event("content_block_delta", delta(0, """{"type":"signature_delta","signature":"si"}""")),
                event("content_block_delta", delta(0, """{"type":"signature_delta","signature":"g"}""")),
                blockStop(0),
                blockStart(1, """{"type":"redacted_thinking","data":"r"}"""),
                blockStop(1),
            )
        val finish = feed(delta("end_turn", 1)).chunks().single()
        val blocks =
            ((finish.delta("llmgw") as JsonValue.Object).members["thinking_blocks"] as JsonValue.Array).items

        assertTrue(before.isEmpty())
        assertEquals(
            JsonValue.Object(
                mapOf(
                    "type" to JsonValue.Text("thinking"),
                    "thinking" to JsonValue.Text("think"),
                    "signature" to JsonValue.Text("sig"),
                ),
            ),
            blocks[0],
        )
        assertEquals(JsonValue.Text("r"), (blocks[1] as JsonValue.Object).members["data"])
    }

    @Test
    fun `thinking and then an in-band error commits nothing`() {
        val events =
            feed(
                START,
                blockStart(0, THINKING_BLOCK),
                blockStop(0),
                error("overloaded_error"),
            )

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.ProviderUnavailable(null))), events)
        assertTrue(stream.ended)
    }

    @Test
    fun `held thinking over the limit is dropped and the answer proceeds`() {
        val small = lifecycle(heldLimit = 4)
        val frames =
            listOf(
                START,
                blockStart(0, """{"type":"redacted_thinking","data":"too long"}"""),
                blockStop(0),
                text("a"),
                delta("end_turn", 1),
            )
        val chunks = small.feed(frames.joinToString("").encodeToByteArray()).chunks()

        assertNull(chunks.last().delta("llmgw"))
        assertEquals(
            "stop",
            chunks
                .last()
                .choices
                .single()
                .finishReason
                ?.value,
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"type":"thinking_delta","thinking":"123456789012"}""",
            """{"type":"signature_delta","signature":"123456789012"}""",
        ],
    )
    fun `an open thinking block past the limit drops all held blocks on append, with no stop`(append: String) {
        // The redacted block fits; only the open block's appends pass the limit.
        val small = lifecycle(heldLimit = 30)
        val frames =
            listOf(
                START,
                blockStart(0, """{"type":"redacted_thinking","data":"r"}"""),
                blockStop(0),
                blockStart(1, THINKING_BLOCK),
                event("content_block_delta", delta(1, append)),
                event("content_block_delta", delta(1, append)),
                text("a"),
                delta("end_turn", 1),
            )
        val chunks = small.feed(frames.joinToString("").encodeToByteArray()).chunks()

        assertNull(chunks.last().delta("llmgw"))
        assertEquals("stop", chunks.last().finish())
    }

    @Test
    fun `deltas after the drop are ignored, and a later stop brings nothing back`() {
        val small = lifecycle(heldLimit = 8)
        val frames =
            listOf(
                START,
                blockStart(0, THINKING_BLOCK),
                event("content_block_delta", delta(0, """{"type":"thinking_delta","thinking":"123456789"}""")),
                event("content_block_delta", delta(0, """{"type":"thinking_delta","thinking":"1"}""")),
                blockStop(0),
                blockStart(1, """{"type":"redacted_thinking","data":"r"}"""),
                blockStop(1),
                delta("end_turn", 1),
            )

        assertNull(
            small
                .feed(frames.joinToString("").encodeToByteArray())
                .chunks()
                .last()
                .delta("llmgw"),
        )
    }

    @Test
    fun `an open thinking block within the limit is kept`() {
        val small = lifecycle(heldLimit = 8)
        val frames =
            listOf(
                START,
                blockStart(0, THINKING_BLOCK),
                event("content_block_delta", delta(0, """{"type":"thinking_delta","thinking":"1234"}""")),
                event("content_block_delta", delta(0, """{"type":"signature_delta","signature":"5678"}""")),
                blockStop(0),
                delta("end_turn", 1),
            )

        assertEquals(
            1,
            small
                .feed(frames.joinToString("").encodeToByteArray())
                .chunks()
                .last()
                .thinkingBlocks()
                .size,
        )
    }

    @Test
    fun `the finish chunk is the first chunk of an empty answer, with the role`() {
        val first = feed(START, delta("end_turn", 0)).chunks().single()

        assertEquals(JsonValue.Text("assistant"), first.delta("role"))
        assertEquals(
            "stop",
            first.choices
                .single()
                .finishReason
                ?.value,
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `a ping in any position changes nothing`(position: Int) {
        val frames = mutableListOf(START, TEXT_START, text("a"), BLOCK_STOP, delta("end_turn", 1), STOP)
        val plain = lifecycle().feed(frames.joinToString("").encodeToByteArray())
        frames.add(position.coerceAtMost(frames.size), PING)

        assertEquals(plain, lifecycle().feed(frames.joinToString("").encodeToByteArray()))
    }

    @Test
    fun `unknown events and unknown delta types change nothing, even unreadable ones`() {
        val events =
            feed(
                START,
                event("something_new", "{not json"),
                event("content_block_delta", delta(0, """{"type":"citations_delta"}""")),
                text("a"),
            )

        assertEquals(1, events.size)
    }

    @Test
    fun `an unknown block type is passed over`() {
        val block = """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"x"}}"""

        assertTrue(feed(START, event("content_block_start", block), blockStop(0)).isEmpty())
    }

    @Test
    fun `a frame split anywhere gives the same result`() {
        val whole = (START + text("héllo") + delta("end_turn", 1) + STOP).encodeToByteArray()
        val expected = lifecycle().feed(whole)
        val split = lifecycle()
        val events = (whole.indices step 7).flatMap { split.feed(whole.copyOfRange(it, minOf(it + 7, whole.size))) }

        assertEquals(expected, events)
    }

    @Test
    fun `message_delta usage is cumulative, the last value wins, and a later stop_reason sends no second finish`() {
        val events =
            feed(
                START,
                text("a"),
                event("message_delta", """{"type":"message_delta","delta":{},"usage":{"output_tokens":2}}"""),
                delta("max_tokens", 3),
                delta("end_turn", 7),
                STOP,
            )
        val chunks = events.chunks()

        assertEquals(1, chunks.count { it.choices.singleOrNull()?.finishReason != null })
        assertEquals(
            "length",
            chunks[1]
                .choices
                .single()
                .finishReason
                ?.value,
        )
        assertEquals(TokenCount(7), chunks.last().usage?.outputTokens)
    }

    @Test
    fun `no usage anywhere gives no usage chunk`() {
        val start = """{"type":"message_start","message":{"id":"msg_1","model":"claude-x"}}"""
        val last = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        val events = feed(event("message_start", start), text("a"), event("message_delta", last), STOP)

        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `a close after a stop_reason completes, with the usage chunk first`() {
        feed(START, text("a"), delta("end_turn", 2))
        val end = stream.finish()

        assertEquals(
            TokenCount(2),
            end
                .chunks()
                .single()
                .usage
                ?.outputTokens,
        )
        assertEquals(ProviderStreamEvent.Completed, end.last())
    }

    @Test
    fun `a close before any stop_reason is UpstreamError`() {
        feed(START, text("a"))

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), stream.finish())
    }

    @Test
    fun `message_stop with no stop_reason finishes with stop and keeps its usage`() {
        val usageOnly = event("message_delta", """{"type":"message_delta","delta":{},"usage":{"output_tokens":5}}""")
        val events = feed(START, text("a"), usageOnly, STOP)
        val chunks = events.chunks()

        assertEquals("stop", chunks[1].finish())
        assertEquals(TokenCount(5), chunks.last().usage?.outputTokens)
        assertEquals(ProviderStreamEvent.Completed, events.last())
        assertTrue(feed(text("late")).isEmpty())
    }

    @Test
    fun `message_stop with no message_delta finishes with stop, and its usage is omitted`() {
        val events = feed(START, text("a"), STOP)

        assertEquals("stop", events.chunks().last().finish())
        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `message_stop with no stop_reason carries held thinking on its finish chunk`() {
        val events = feed(START, blockStart(0, """{"type":"redacted_thinking","data":"r"}"""), blockStop(0), STOP)

        assertEquals("stop", events.chunks().single().finish())
        assertEquals(
            1,
            events
                .chunks()
                .single()
                .thinkingBlocks()
                .size,
        )
    }

    @Test
    fun `message_delta with no usage omits it, whatever message_start said of output`() {
        val last = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        val events = feed(START, text("a"), event("message_delta", last), STOP)

        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `message_delta with an unreadable output count omits usage, whatever message_start said of output`() {
        val last = """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":"x"}}"""
        val events = feed(START, text("a"), event("message_delta", last), STOP)

        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @Test
    fun `a close with no output count in message_delta omits usage too`() {
        val last = """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}"""
        feed(START, text("a"), event("message_delta", last))

        assertEquals(listOf(ProviderStreamEvent.Completed), stream.finish())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cache_read_input_tokens", "cache_creation_input_tokens"])
    fun `an unreadable cache count in message_start omits usage`(name: String) {
        val start =
            """{"type":"message_start","message":{"id":"m","model":"claude-x",
            "usage":{"input_tokens":10,"$name":"x"}}}""".replace("\n", "")
        val events = feed(event("message_start", start), text("a"), delta("end_turn", 5), STOP)

        assertTrue(events.chunks().none { it.usage != null })
        assertEquals(ProviderStreamEvent.Completed, events.last())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cache_read_input_tokens", "cache_creation_input_tokens"])
    fun `an unreadable cache count in message_delta omits usage`(name: String) {
        val last =
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},
            "usage":{"output_tokens":5,"$name":-1}}""".replace("\n", "")
        val events = feed(START, text("a"), event("message_delta", last), STOP)

        assertTrue(events.chunks().none { it.usage != null })
    }

    @Test
    fun `a truncated tool call at max_tokens reaches the client as received`() {
        val chunks =
            feed(START, toolStart(0, "t"), json(0, """{"city":"Par"""), blockStop(0), delta("max_tokens", 5), STOP)
                .chunks()

        assertEquals(JsonValue.Text("""{"city":"Par"""), chunks[1].toolCall().function("arguments"))
        assertEquals(
            listOf("length"),
            chunks.mapNotNull {
                it.choices
                    .singleOrNull()
                    ?.finishReason
                    ?.value
            },
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop"],
    )
    fun `an event that needs message_start before it is UpstreamError`(name: String) {
        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)),
            feed(event(name, """{"type":"$name","index":0}""")),
        )
    }

    @Test
    fun `message_start with no id is UpstreamError`() {
        assertEquals(
            listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)),
            feed(event("message_start", """{"type":"message_start","message":{"model":"m"}}""")),
        )
    }

    @Test
    fun `unreadable JSON in a known event is UpstreamError`() {
        val events = feed(START, "event: content_block_delta\ndata: {not json\n\n")

        assertEquals(listOf(ProviderStreamEvent.Failed(ProviderFailure.UpstreamError)), events)
    }

    @Test
    fun `an in-band error after content fails by its type, its fields go to the log only`() {
        val events = feed(START, text("a"), error("rate_limit_error"))

        assertEquals(ProviderStreamEvent.Failed(ProviderFailure.UpstreamRateLimited(null)), events.last())
        assertEquals("rate_limit_error", stream.errorFields?.type)
    }

    @Test
    fun `after message_stop anything is ignored and the stream drains`() {
        feed(START, delta("end_turn", 1), STOP)

        assertTrue(feed(text("late")).isEmpty())
        assertTrue(stream.finish().isEmpty())
        assertFalse(stream.ended)
    }

    private fun blockStart(
        index: Int,
        block: String,
    ): String = event("content_block_start", """{"type":"content_block_start","index":$index,"content_block":$block}""")

    private fun text(value: String): String =
        event("content_block_delta", delta(0, """{"type":"text_delta","text":"$value"}"""))

    private fun json(
        index: Int,
        partial: String,
    ): String {
        val escaped = partial.replace("\"", "\\\"")
        return event("content_block_delta", delta(index, """{"type":"input_json_delta","partial_json":"$escaped"}"""))
    }

    private fun delta(
        index: Int,
        delta: String,
    ): String = """{"type":"content_block_delta","index":$index,"delta":$delta}"""

    private fun delta(
        stopReason: String,
        output: Int,
    ): String =
        event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},
            "usage":{"output_tokens":$output}}""".replace("\n", ""),
        )

    private fun toolStart(
        index: Int,
        id: String,
    ): String =
        event(
            "content_block_start",
            """{"type":"content_block_start","index":$index,
            "content_block":{"type":"tool_use","id":"$id","name":"get","input":{}}}""".replace("\n", ""),
        )

    private fun blockStop(index: Int): String =
        event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    private fun error(type: String): String =
        event("error", """{"type":"error","error":{"type":"$type","message":"secret"}}""")

    private companion object {
        const val CREATED = 1_793_750_400L
        const val LIMIT = 1_048_576
        const val THINKING_BLOCK = """{"type":"thinking","thinking":"","signature":""}"""
        const val PING = "event: ping\ndata: {\"type\":\"ping\"}\n\n"
        const val STOP = "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        const val BLOCK_STOP = "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
        const val TEXT_START =
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0," +
                "\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
        const val START =
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\"," +
                "\"model\":\"claude-x\",\"content\":[],\"usage\":{\"input_tokens\":10," +
                "\"cache_read_input_tokens\":3,\"cache_creation_input_tokens\":2,\"output_tokens\":1}}}\n\n"
    }
}
