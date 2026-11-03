package io.github.pdroacordi.llmgateway.mockprovider.anthropic

import io.github.pdroacordi.llmgateway.mockprovider.error.MockRejection
import io.github.pdroacordi.llmgateway.mockprovider.generation.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper

class AnthropicRequestsTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `system string, text blocks and tool_result text count`() {
        val request =
            AnthropicRequests.messages(
                tree(
                    """
                    {"model":"mock-claude","max_tokens":64,"stream":true,"system":"be brief",
                     "messages":[{"role":"user","content":"hello"},
                     {"role":"assistant","content":[{"type":"text","text":"calling"},{"type":"tool_use","id":"t"}]},
                     {"role":"user","content":[{"type":"tool_result","tool_use_id":"t","content":"42"},
                      {"type":"tool_result","tool_use_id":"u","content":[{"type":"text","text":"43"}]},
                      {"type":"image","source":{}}]}]}
                    """,
                ),
            )

        assertEquals("mock-claude", request.model)
        assertEquals(64, request.cap)
        assertTrue(request.stream)
        assertEquals(
            listOf(
                Turn("system", "be brief"),
                Turn("user", "hello"),
                Turn("assistant", "calling"),
                Turn("user", "42\n43"),
            ),
            request.conversation.turns,
        )
    }

    @Test
    fun `a system array of text blocks, no stream by default`() {
        val request =
            AnthropicRequests.messages(
                tree(
                    """{"model":"m","max_tokens":0,"system":[{"type":"text","text":"a"},{"type":"text","text":"b"}],
                    "messages":[{"role":"user","content":"x"}]}""",
                ),
            )

        assertEquals(Turn("system", "a\nb"), request.conversation.turns.first())
        assertFalse(request.stream)
        assertEquals(0, request.cap)
    }

    @ParameterizedTest(name = "{0} -> param {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """{"max_tokens":1,"messages":[{"role":"user","content":"x"}]}|model""",
            """{"model":"m","messages":[{"role":"user","content":"x"}]}|max_tokens""",
            """{"model":"m","max_tokens":-1,"messages":[{"role":"user","content":"x"}]}|max_tokens""",
            """{"model":"m","max_tokens":1.5,"messages":[{"role":"user","content":"x"}]}|max_tokens""",
            """{"model":"m","max_tokens":1}|messages""",
            """{"model":"m","max_tokens":1,"messages":[]}|messages""",
            """{"model":"m","max_tokens":1,"system":3,"messages":[{"role":"user","content":"x"}]}|system""",
            """{"model":"m","max_tokens":1,"messages":[{"role":"user","content":"x"}],"stream":1}|stream""",
        ],
    )
    fun `validation gives 400 with the param`(
        body: String,
        param: String,
    ) {
        val rejection = assertThrows<MockRejection> { AnthropicRequests.messages(tree(body)) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
    }

    private fun tree(json: String) = mapper.readTree(json.trimIndent())
}
