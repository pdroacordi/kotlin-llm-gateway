package io.github.pdroacordi.llmgateway.gateway.providers.openai

import io.github.pdroacordi.llmgateway.gateway.core.chat.FinishReason
import io.github.pdroacordi.llmgateway.gateway.core.chat.ModelName
import io.github.pdroacordi.llmgateway.gateway.core.chat.TokenCount
import io.github.pdroacordi.llmgateway.gateway.core.json.JsonValue
import io.github.pdroacordi.llmgateway.gateway.providers.json.exactTreeReader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class OpenAiResponseReaderTest {
    private val reader = OpenAiResponseReader(JsonMapper.builder().build().exactTreeReader())

    private fun read(json: String) = reader.read(json.encodeToByteArray())

    private fun readable(json: String) = checkNotNull(read(json)) { json }

    @Test
    fun `the golden FND-03 body is read`() {
        val result = readable(GOLDEN)
        val completion = result.completion
        val choice = completion.choices.single()

        assertEquals("chatcmpl-mock-000000000001", completion.id)
        assertEquals(1793750400L, completion.created)
        assertEquals(ModelName("mock-chat"), completion.model)
        assertEquals(0, choice.index)
        assertEquals("Hi there", choice.message.content)
        assertEquals(FinishReason("stop"), choice.finishReason)
    }

    @Test
    fun `members the gateway does not model are kept, in order, at each level`() {
        val result = readable(GOLDEN)
        val completion = result.completion
        val choice = completion.choices.single()

        assertEquals(
            listOf("service_tier", "system_fingerprint"),
            completion.extras.members.keys
                .toList(),
        )
        assertEquals(JsonValue.Text("fp_mock"), completion.extras.members["system_fingerprint"])
        assertEquals(mapOf("logprobs" to JsonValue.Null), choice.extras.members)
        assertEquals(
            listOf("refusal", "annotations"),
            choice.message.extras.members.keys
                .toList(),
        )
        assertEquals(JsonValue.Null, choice.message.extras.members["refusal"])
    }

    @Test
    fun `usage is read, with its detail objects kept`() {
        val usage = checkNotNull(readable(GOLDEN).usage)

        assertEquals(TokenCount(14), usage.inputTokens)
        assertEquals(TokenCount(2), usage.outputTokens)
        assertEquals(TokenCount(0), usage.cachedInputTokens)
        assertFalse(usage.estimated)
        assertEquals(
            listOf("prompt_tokens_details", "completion_tokens_details"),
            usage.extras.members.keys
                .toList(),
        )
    }

    @Test
    fun `a valid cached_tokens is read`() {
        val body = GOLDEN.replace(""""cached_tokens":0""", """"cached_tokens":9""")

        assertEquals(TokenCount(9), readable(body).usage?.cachedInputTokens)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"9\"", "-1", "1.5", "null"])
    fun `a cached_tokens that is not a count gives no cached count, not a failure`(value: String) {
        val body = GOLDEN.replace(""""cached_tokens":0""", """"cached_tokens":$value""")

        val usage = checkNotNull(readable(body).usage)

        assertNull(usage.cachedInputTokens)
        assertEquals(TokenCount(14), usage.inputTokens)
    }

    @Test
    fun `absent usage is null`() {
        val body = GOLDEN.replace(USAGE, "")

        assertNull(readable(body).usage)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "null",
            "5",
            "{}",
            """{"prompt_tokens":1}""",
            """{"completion_tokens":1}""",
            """{"prompt_tokens":"1","completion_tokens":1}""",
            """{"prompt_tokens":1.5,"completion_tokens":1}""",
            """{"prompt_tokens":-1,"completion_tokens":1}""",
            """{"prompt_tokens":1,"completion_tokens":123456789012345678901234567890}""",
            """{"prompt_tokens":9223372036854775807,"completion_tokens":1}""",
        ],
    )
    fun `malformed usage counts as omitted, so a paid answer is not thrown away`(usage: String) {
        val body = GOLDEN.replace(USAGE, """"usage":$usage,""")

        assertNull(readable(body).usage)
    }

    @Test
    fun `null content and a null finish reason are readable`() {
        val body =
            GOLDEN
                .replace(""""content":"Hi there"""", """"content":null""")
                .replace(""""finish_reason":"stop"""", """"finish_reason":null""")
        val choice = readable(body).completion.choices.single()

        assertNull(choice.message.content)
        assertNull(choice.finishReason)
    }

    // What typed members change, GW-01 section 4: "Narrower than OpenAI".
    @Test
    fun `absent content and an absent finish reason read as null`() {
        val choice =
            readable("""{"id":"a","created":1,"model":"m","choices":[{"index":0,"message":{}}]}""")
                .completion.choices
                .single()

        assertNull(choice.message.content)
        assertNull(choice.finishReason)
    }

    @Test
    fun `a finish reason that is not a string reads as null`() {
        val choice =
            readable(GOLDEN.replace(""""finish_reason":"stop"""", """"finish_reason":5"""))
                .completion.choices
                .single()

        assertNull(choice.finishReason)
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"1\"", "1.5", "null", "12345678901"])
    fun `an index that is not an integer reads as 0`(index: String) {
        val choice =
            readable(GOLDEN.replace(""""index":0""", """"index":$index"""))
                .completion.choices
                .single()

        assertEquals(0, choice.index)
    }

    @Test
    fun `tool calls ride in the message extras`() {
        val body = GOLDEN.replace(""""refusal":null""", """"tool_calls":[{"id":"c1"}]""")

        val extras =
            readable(body)
                .completion.choices
                .single()
                .message.extras.members

        assertEquals(setOf("tool_calls", "annotations"), extras.keys)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"id":"chatcmpl-mock-000000000001",""",
            """"created":1793750400,""",
            """"model":"mock-chat",""",
        ],
    )
    fun `each missing required member makes the body unreadable`(member: String) {
        assertNull(read(GOLDEN.replace(member, "")))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"id":5,"created":1,"model":"m","choices":[{"message":{}}]}""",
            """{"id":"a","created":"now","model":"m","choices":[{"message":{}}]}""",
            """{"id":"a","created":1.5,"model":"m","choices":[{"message":{}}]}""",
            """{"id":"a","created":1,"model":7,"choices":[{"message":{}}]}""",
            """{"id":"a","created":1,"model":"  ","choices":[{"message":{}}]}""",
            """{"id":"a","created":1,"model":"m","choices":[]}""",
            """{"id":"a","created":1,"model":"m","choices":{}}""",
            """{"id":"a","created":1,"model":"m"}""",
            """{"id":"a","created":1,"model":"m","choices":[5]}""",
            """{"id":"a","created":1,"model":"m","choices":[{"index":0}]}""",
            """{"id":"a","created":1,"model":"m","choices":[{"message":"text"}]}""",
            """{"id":"a","created":1,"model":"m","choices":[{"message":{"content":5}}]}""",
            """{"id":"a","created":1,"model":"m","choices":[{"message":{}},{"index":1}]}""",
        ],
    )
    fun `a required member of the wrong shape makes the body unreadable`(body: String) {
        assertNull(read(body))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "not json", "[]", "null", "5", """{"id":""", """{"id":1e99999999999}"""])
    fun `a body that is not an object is unreadable`(body: String) {
        assertNull(read(body))
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["1.5", "null", "\"1\"", "1e99"])
    fun `an index that is not an integer reads as 0, and does not throw`(index: String) {
        val body =
            """{"id":"c","created":1,"model":"m","choices":[{"index":$index,"message":{"content":"Hi"}}]}"""

        val result =
            checkNotNull(
                OpenAiResponseReader(JsonMapper.builder().build().exactTreeReader()).read(body.encodeToByteArray()),
            )

        assertEquals(
            0,
            result.completion.choices
                .single()
                .index,
        )
    }

    private companion object {
        const val USAGE =
            """"usage":{"prompt_tokens":14,"completion_tokens":2,"total_tokens":16,""" +
                """"prompt_tokens_details":{"cached_tokens":0},"completion_tokens_details":{"reasoning_tokens":0}},"""
        const val GOLDEN =
            """{"id":"chatcmpl-mock-000000000001","object":"chat.completion","created":1793750400,""" +
                """"model":"mock-chat","choices":[{"index":0,"message":{"role":"assistant","content":"Hi there",""" +
                """"refusal":null,"annotations":[]},"logprobs":null,"finish_reason":"stop"}],""" +
                USAGE +
                """"service_tier":"default","system_fingerprint":"fp_mock"}"""
    }
}
