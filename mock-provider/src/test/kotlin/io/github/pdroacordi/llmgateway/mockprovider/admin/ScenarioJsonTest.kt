package io.github.pdroacordi.llmgateway.mockprovider.admin

import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Behaviour
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentMode
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ContentSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Fault
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.FaultKind
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.InjectedError
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Latency
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Scenario
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ScriptedFinish
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.Step
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ThinkingSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.ToolCallSpec
import io.github.pdroacordi.llmgateway.mockprovider.behaviour.UsageSpec
import io.github.pdroacordi.llmgateway.mockprovider.error.MockRejection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.jackson.databind.json.JsonMapper

class ScenarioJsonTest {
    private val mapper = JsonMapper.builder().build()
    private val json = ScenarioJson(mapper, maxHoldMs = 300_000)

    @Test
    fun `a full scenario parses`() {
        val scenario =
            json.parse(
                tree(
                    """
                    {"steps":[{"times":2,"error":{"status":429,"retryAfterSeconds":2,"retryAfterMs":2000,"type":"t",
                      "code":"c","message":"m","param":"p","headers":{"x-ratelimit-remaining-requests":"0"}}},
                      {"fault":{"kind":"stream_error","afterPieces":3},"error":{"status":529}},
                      {"latency":{"ttftMs":800,"perTokenMs":5,"jitterMs":1},"content":{"mode":"fixed","text":"hi",
                       "completionTokens":4},"usage":{"omit":true,"cachedTokens":1,"cacheCreationTokens":2,
                       "promptTokens":3,"completionTokens":4}}],
                     "otherwise":{"fault":{"kind":"hang"}}}
                    """,
                ),
            )

        assertEquals(
            Scenario(
                steps =
                    listOf(
                        Step(
                            Behaviour(
                                error =
                                    InjectedError(
                                        status = 429,
                                        type = "t",
                                        code = "c",
                                        message = "m",
                                        param = "p",
                                        retryAfterSeconds = 2,
                                        retryAfterMs = 2000,
                                        headers = mapOf("x-ratelimit-remaining-requests" to "0"),
                                    ),
                            ),
                            times = 2,
                        ),
                        Step(Behaviour(error = InjectedError(529), fault = Fault(FaultKind.STREAM_ERROR, 3))),
                        Step(
                            Behaviour(
                                latency = Latency(800, 5, 1),
                                content = ContentSpec(ContentMode.FIXED, "hi", 4),
                                usage = UsageSpec(true, 1, 2, 3, 4),
                            ),
                        ),
                    ),
                otherwise = Behaviour(fault = Fault(FaultKind.HANG, 0)),
            ),
            scenario,
        )
    }

    @Test
    fun `an empty object is a scenario with no steps and no otherwise`() {
        assertEquals(Scenario(emptyList(), null), json.parse(tree("{}")))
    }

    @Test
    fun `a scenario round-trips through its JSON form`() {
        val text =
            """{"steps":[{"times":1,"latency":{"ttftMs":5}},{"times":3,"error":{"status":503,"headers":{}}}],""" +
                """"otherwise":{"content":{"mode":"echo"}}}"""
        val scenario = json.parse(tree(text))

        assertEquals(scenario, json.parse(json.write(scenario)))
        assertEquals(text, mapper.writeValueAsString(json.write(scenario)))
    }

    @Test
    fun `the largest allowed times and step count are accepted`() {
        val steps = List(1000) { """{"times":1000000}""" }.joinToString(",")

        assertEquals(1000, json.parse(tree("""{"steps":[$steps]}""")).steps.size)
    }

    @Test
    fun `more than 1000 steps is a 400 naming steps`() {
        val steps = List(1001) { "{}" }.joinToString(",")

        val rejection = assertThrows<MockRejection> { json.parse(tree("""{"steps":[$steps]}""")) }

        assertEquals("steps", rejection.param)
    }

    // Netty's rule: only the first character may not be a tab.
    @Test
    fun `an embedded tab and a trailing space in a header value are accepted`() {
        val scenario = """{"steps":[{"error":{"status":429,"headers":{"x-a":"a\tb "}}}]}"""

        assertEquals(
            "a\tb ",
            json
                .parse(tree(scenario))
                .steps
                .single()
                .behaviour.error
                ?.headers
                ?.get("x-a"),
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            """[]|scenario""",
            """{"step":[]}|step""",
            """{"steps":{}}|steps""",
            """{"steps":[{"latency":{"ttftMs":-1}}]}|steps[0].latency.ttftMs""",
            """{"steps":[{"latency":{"perTokenMs":300001}}]}|steps[0].latency.perTokenMs""",
            """{"steps":[{"latency":{"ttft":1}}]}|steps[0].latency.ttft""",
            """{"steps":[{"error":{"status":399}}]}|steps[0].error.status""",
            """{"steps":[{"error":{"status":600}}]}|steps[0].error.status""",
            """{"steps":[{"error":{}}]}|steps[0].error.status""",
            """{"steps":[{"error":{"status":500,"headers":{"a":1}}}]}|steps[0].error.headers.a""",
            """{"steps":[{"times":0,"error":{"status":500}}]}|steps[0].times""",
            """{"steps":[{"times":1000001}]}|steps[0].times""",
            """{"steps":[{"times":2147483647}]}|steps[0].times""",
            """{"steps":[{"error":{"status":429,"headers":{"bad name":"x"}}}]}|steps[0].error.headers.bad name""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"v\r\nx-b: injected"}}}]}|""" +
                """steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"v\u0000"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"a\u0001b"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"a\u000bb"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"a\u007fb"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"\u007fb"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":" v"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"x-a":"\tv"}}}]}|steps[0].error.headers.x-a""",
            """{"steps":[{"error":{"status":429,"headers":{"":"v"}}}]}|steps[0].error.headers.""",
            """{"steps":[{"fault":{"kind":"explode"}}]}|steps[0].fault.kind""",
            """{"steps":[{"fault":{"kind":"stall","afterPieces":-1}}]}|steps[0].fault.afterPieces""",
            """{"steps":[{"fault":{"kind":"hang"},"error":{"status":500}}]}|steps[0].fault""",
            """{"steps":[{"content":{"mode":"fixed"}}]}|steps[0].content.text""",
            """{"steps":[{"content":{"mode":"poem"}}]}|steps[0].content.mode""",
            """{"steps":[{"content":{"completionTokens":4097}}]}|steps[0].content.completionTokens""",
            """{"steps":[{"usage":{"omit":"yes"}}]}|steps[0].usage.omit""",
            """{"steps":[{"usage":{"cachedTokens":-1}}]}|steps[0].usage.cachedTokens""",
            """{"otherwise":{"times":2}}|otherwise.times""",
            """{"otherwise":{"bogus":true}}|otherwise.bogus""",
        ],
    )
    fun `every rejection is a 400 naming the member`(
        body: String,
        param: String,
    ) {
        val rejection = assertThrows<MockRejection> { json.parse(tree(body)) }

        assertEquals(400, rejection.status)
        assertEquals(param, rejection.param)
    }

    private fun tree(text: String) = mapper.readTree(text.trimIndent())

    @Test
    fun `tool calls, finish and thinking parse and write back unchanged`() {
        val text =
            """{"otherwise":{"content":{"mode":"fixed","text":"x","toolCalls":[{"id":"t_1","name":"get",
            "input":{"city":"Paris","n":1},"chunkChars":3}],"finish":"refusal",
            "thinking":[{"signature":"s"},{"redacted":"r"}]}}}"""
        val scenario = json.parse(tree(text))
        val content = checkNotNull(scenario.otherwise?.content)

        assertEquals(listOf(ToolCallSpec("t_1", "get", """{"city":"Paris","n":1}""", 3)), content.toolCalls)
        assertEquals(ScriptedFinish.REFUSAL, content.finish)
        assertEquals(listOf(ThinkingSpec("s"), ThinkingSpec(redacted = "r")), content.thinking)
        assertEquals(scenario, json.parse(json.write(scenario)))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            """{"toolCalls":{}} | toolCalls""",
            """{"toolCalls":[{"input":{}}]} | toolCalls[0].name""",
            """{"toolCalls":[{"name":"a b","input":{}}]} | toolCalls[0].name""",
            """{"toolCalls":[{"name":"a"}]} | toolCalls[0].input""",
            """{"toolCalls":[{"name":"a","input":[]}]} | toolCalls[0].input""",
            """{"toolCalls":[{"name":"a","input":{},"id":"b c"}]} | toolCalls[0].id""",
            """{"toolCalls":[{"name":"a","input":{},"chunkChars":0}]} | toolCalls[0].chunkChars""",
            """{"toolCalls":[{"name":"a","input":{},"chunkChars":4097}]} | toolCalls[0].chunkChars""",
            """{"toolCalls":[{"name":"a","input":{},"x":1}]} | toolCalls[0].x""",
            """{"finish":"stop"} | finish""",
            """{"thinking":{}} | thinking""",
            """{"thinking":[{}]} | thinking[0]""",
            """{"thinking":[{"signature":""}]} | thinking[0]""",
            """{"thinking":[{"signature":"a","redacted":"b"}]} | thinking[0]""",
            """{"thinking":[{"x":"a"}]} | thinking[0].x""",
        ],
    )
    fun `a scripted content out of bounds names its member`(
        content: String,
        param: String,
    ) {
        val rejection = assertThrows<MockRejection> { json.parse(tree("""{"otherwise":{"content":$content}}""")) }

        assertEquals("otherwise.content.$param", rejection.param)
    }

    @Test
    fun `at most 64 tool calls and 16 thinking blocks`() {
        val calls = List(65) { """{"name":"a","input":{}}""" }.joinToString(",")
        val blocks = List(17) { """{"signature":"s"}""" }.joinToString(",")
        val tooManyCalls = tree("""{"otherwise":{"content":{"toolCalls":[$calls]}}}""")
        val tooManyBlocks = tree("""{"otherwise":{"content":{"thinking":[$blocks]}}}""")

        assertEquals("otherwise.content.toolCalls", assertThrows<MockRejection> { json.parse(tooManyCalls) }.param)
        assertEquals("otherwise.content.thinking", assertThrows<MockRejection> { json.parse(tooManyBlocks) }.param)
    }
}
