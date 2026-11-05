package io.github.pdroacordi.llmgateway.mockprovider.openai

import io.github.pdroacordi.llmgateway.mockprovider.error.MockRejection
import io.github.pdroacordi.llmgateway.mockprovider.generation.EmbeddingInput
import io.github.pdroacordi.llmgateway.mockprovider.generation.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper

class OpenAiRequestsTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a chat request gives the conversation, flags and cap`() {
        val chat =
            OpenAiRequests.chat(
                tree(
                    """
                    {"model":"mock-chat","stream":true,"stream_options":{"include_usage":true},"max_tokens":9,
                     "max_completion_tokens":5,"temperature":0.3,"tools":[{"type":"function"}],
                     "messages":[{"role":"system","content":"s"},{"role":"user","content":[{"type":"text","text":"a"},
                     {"type":"image_url","image_url":{"url":"x"}},{"type":"text","text":"b"}]},
                     {"role":"assistant","content":null,"tool_calls":[{"id":"c"}]},
                     {"role":"tool","tool_call_id":"c","content":"r"}]}
                    """,
                ),
            )

        assertEquals("mock-chat", chat.model)
        assertEquals(
            listOf(Turn("system", "s"), Turn("user", "a\nb"), Turn("assistant", ""), Turn("tool", "r")),
            chat.conversation.turns,
        )
        assertTrue(chat.stream)
        assertTrue(chat.includeUsage)
        assertEquals(5, chat.cap)
    }

    @Test
    fun `defaults - no stream, no usage chunk, no cap`() {
        val chat = OpenAiRequests.chat(tree("""{"model":"m","messages":[{"role":"user","content":"x"}],"n":1}"""))

        assertFalse(chat.stream)
        assertFalse(chat.includeUsage)
        assertNull(chat.cap)
    }

    @ParameterizedTest(name = "{0} -> param {1}")
    @CsvSource(
        delimiter = '|',
        nullValues = ["null"],
        value = [
            """[]|null|null""",
            """{"messages":[{"role":"user","content":"x"}]}|model|null""",
            """{"model":3,"messages":[{"role":"user","content":"x"}]}|model|null""",
            """{"model":"m"}|messages|null""",
            """{"model":"m","messages":[]}|messages|null""",
            """{"model":"m","messages":[{"role":"user","content":"x"}],"n":2}|n|unsupported_value""",
            """{"model":"m","messages":[{"role":"user","content":"x"}],"max_tokens":-1}|max_tokens|null""",
            """{"model":"m","messages":[{"role":"user","content":"x"}],"max_completion_tokens":"a"}|""" +
                """max_completion_tokens|null""",
            """{"model":"m","messages":[{"role":"user","content":"x"}],"stream":"yes"}|stream|null""",
            """{"model":"m","messages":["x"]}|messages[0]|null""",
            """{"model":"m","messages":[{"content":"x"}]}|messages[0].role|null""",
            """{"model":"m","messages":[{"role":"user","content":3}]}|messages[0].content|null""",
        ],
    )
    fun `chat validation gives 400 with the param`(
        body: String,
        param: String?,
        code: String?,
    ) {
        val rejection = assertThrows<MockRejection> { OpenAiRequests.chat(tree(body)) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
        assertEquals(code, rejection.code)
    }

    @ParameterizedTest(name = "{0} -> param {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """[$USER,$ASKED,{"role":"tool","tool_call_id":"other","content":"r"}]|messages[2]""",
            """[$USER,$ASKED,{"role":"tool","content":"r"}]|messages[2]""",
            """[$USER,$ASKED,{"role":"tool","tool_call_id":null,"content":"r"}]|messages[2]""",
            """[$USER,$ASKED,{"role":"tool","tool_call_id":7,"content":"r"}]|messages[2]""",
            """[$USER,{"role":"tool","tool_call_id":"a","content":"r"}]|messages[1]""",
            """[{"role":"tool","tool_call_id":"a","content":"r"}]|messages[0]""",
            """[$USER,$ASKED,$ANSWER_A,{"role":"tool","tool_call_id":"z","content":"r"}]|messages[3]""",
            """[$USER,$ASKED,$ANSWER_A,$USER,$ANSWER_B]|messages[4]""",
            """[$USER,$ASKED,$USER,$ANSWER_A]|messages[3]""",
        ],
    )
    fun `a tool message answering no call of the preceding tool_calls is 400 naming it`(
        messages: String,
        param: String,
    ) {
        val rejection =
            assertThrows<MockRejection> { OpenAiRequests.chat(tree("""{"model":"m","messages":$messages}""")) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
        assertTrue(param in rejection.message.orEmpty()) { rejection.message }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """[$USER,$ASKED,$ANSWER_A]""",
            """[$USER,$ASKED,$ANSWER_A,$ANSWER_B]""",
            """[$USER,$ASKED,$ANSWER_B,$ANSWER_A,$USER]""",
            """[$USER,$ASKED]""",
            """[$USER,{"role":"assistant","content":"Hi"},$USER]""",
        ],
    )
    fun `a conversation that answers what was asked, or asks nothing, passes the check`(messages: String) {
        val chat = OpenAiRequests.chat(tree("""{"model":"m","messages":$messages}"""))

        assertEquals("m", chat.model)
    }

    @Test
    fun `embeddings accept a string, strings, a token array and token arrays`() {
        assertEquals(
            listOf(EmbeddingInput.Text("a")),
            OpenAiRequests.embeddings(tree("""{"model":"e","input":"a"}"""), LIMITS).inputs,
        )
        assertEquals(
            listOf(EmbeddingInput.Text("a"), EmbeddingInput.Text("b")),
            OpenAiRequests.embeddings(tree("""{"model":"e","input":["a","b"]}"""), LIMITS).inputs,
        )
        assertEquals(
            listOf(EmbeddingInput.Tokens(listOf(1, 2))),
            OpenAiRequests.embeddings(tree("""{"model":"e","input":[1,2]}"""), LIMITS).inputs,
        )
        assertEquals(
            listOf(EmbeddingInput.Tokens(listOf(1)), EmbeddingInput.Tokens(listOf(2, 3))),
            OpenAiRequests.embeddings(tree("""{"model":"e","input":[[1],[2,3]]}"""), LIMITS).inputs,
        )
    }

    @Test
    fun `embeddings take at most 2048 inputs`() {
        val inputs = { n: Int -> List(n) { "\"w\"" }.joinToString(",", "[", "]") }

        assertEquals(
            2048,
            OpenAiRequests.embeddings(tree("""{"model":"e","input":${inputs(2048)}}"""), LIMITS).inputs.size,
        )
        val rejection =
            assertThrows<MockRejection> {
                OpenAiRequests.embeddings(
                    tree("""{"model":"e","input":${inputs(2049)}}"""),
                    LIMITS,
                )
            }
        assertEquals("input", rejection.param)
    }

    @Test
    fun `embeddings default to 1536 float dimensions`() {
        val request = OpenAiRequests.embeddings(tree("""{"model":"e","input":"a"}"""), LIMITS)
        val custom =
            OpenAiRequests.embeddings(
                tree("""{"model":"e","input":"a","dimensions":3,"encoding_format":"base64"}"""),
                LIMITS,
            )

        assertEquals(1536, request.dimensions)
        assertFalse(request.base64)
        assertEquals(3, custom.dimensions)
        assertTrue(custom.base64)
    }

    @ParameterizedTest(name = "{0} -> param {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """{"input":"a"}|model""",
            """{"model":"e"}|input""",
            """{"model":"e","input":""}|input""",
            """{"model":"e","input":[]}|input""",
            """{"model":"e","input":["a",""]}|input""",
            """{"model":"e","input":["a",1]}|input""",
            """{"model":"e","input":[[]]}|input""",
            """{"model":"e","input":"a","dimensions":0}|dimensions""",
            """{"model":"e","input":"a","dimensions":3073}|dimensions""",
            """{"model":"e","input":"a","encoding_format":"int8"}|encoding_format""",
        ],
    )
    fun `embeddings validation gives 400 with the param`(
        body: String,
        param: String,
    ) {
        val rejection = assertThrows<MockRejection> { OpenAiRequests.embeddings(tree(body), LIMITS) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
    }

    private fun tree(json: String) = mapper.readTree(json.trimIndent())

    private companion object {
        const val USER = """{"role":"user","content":"x"}"""
        const val ASKED = """{"role":"assistant","content":null,"tool_calls":[{"id":"a"},{"id":"b"}]}"""
        const val ANSWER_A = """{"role":"tool","tool_call_id":"a","content":"r"}"""
        const val ANSWER_B = """{"role":"tool","tool_call_id":"b","content":"r"}"""
        val LIMITS = EmbeddingLimits(defaultDimensions = 1536, maxDimensions = 3072)
    }
}
